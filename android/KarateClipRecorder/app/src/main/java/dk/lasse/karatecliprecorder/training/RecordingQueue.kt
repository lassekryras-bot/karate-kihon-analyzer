package dk.lasse.karatecliprecorder.training

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

object RecordingQueue {
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        // The periodic sweep covers process death, charging/battery recovery and a lost wake-up.
        manager.enqueueUniquePeriodicWork("recording-queue-sweep", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecordingQueueWorker>(15, TimeUnit.MINUTES).build())
        // Each wake-up can bypass an older battery/capture retry. Room orders jobs; the heavy
        // lock serializes workers. A newer wake-up never cancels an already-running extraction.
        manager.enqueue(OneTimeWorkRequestBuilder<RecordingQueueWorker>()
            .addTag("recording-queue").setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun processNow(context: Context, id: String, completed: (kotlin.Result<Unit>) -> Unit) {
        val preferences = ProcessingPreferences(context)
        if (!ProcessingPolicy.mayStart(ProcessingPolicy.snapshot(context), preferences.minimumBattery)) {
            completed(kotlin.Result.failure(IllegalStateException("Connect a charger: battery is below ${preferences.minimumBattery}%."))); return
        }
        TrainingServices.get(context).submit({ it.promote(id); Unit }) {
            if (it.isSuccess) schedule(context)
            completed(it)
        }
    }
    fun delete(context: Context, id: String, completed: (kotlin.Result<Unit>) -> Unit) {
        // Immediate cancellation signal; the same fence prevents any post-delete file publication.
        ProcessingCoordinator.markDeleted(id)
        TrainingServices.get(context).submit({ repo ->
            synchronized(ProcessingCoordinator.publication) { repo.deleteRecordingAndEvidence(id) }
        }) { result -> schedule(context); completed(result) }
    }
}

class RecordingQueueWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val service = TrainingServices.get(applicationContext)
        val ready = java.util.concurrent.CompletableFuture<Unit>()
        service.submit({ Unit }) { ready.complete(Unit) }
        ready.get()
        if (!ProcessingCoordinator.heavy.tryLock()) return Result.success()
        try {
            val repo = service.repository
            repo.recoverJobs()
            repo.jobs().filter { it.state == QueueState.DELETING }.forEach {
                synchronized(ProcessingCoordinator.publication) { repo.deleteRecordingAndEvidence(it.sessionId) }
            }
            while (!isStopped) {
                val preferences = ProcessingPreferences(applicationContext)
                if (ProcessingCoordinator.capturing) return Result.retry()
                val job = repo.jobs().firstOrNull { it.state == QueueState.QUEUED &&
                    ProcessingPolicy.mayProcess(preferences.background, ProcessingCoordinator.foregroundActivities.get() > 0, it.manual) }
                    ?: return Result.success()
                if (!ProcessingPolicy.mayStart(ProcessingPolicy.snapshot(applicationContext), preferences.minimumBattery)) return Result.retry()
                repo.updateJob(job.copy(state = QueueState.PROCESSING, phase = ProcessingPhase.LANDMARKS, error = null))
                try {
                    setForegroundAsync(foregroundInfo()).get()
                    val check = {
                        val current = repo.job(job.sessionId)
                        val allowed = ProcessingPolicy.mayProcess(ProcessingPreferences(applicationContext).background,
                            ProcessingCoordinator.foregroundActivities.get() > 0, job.manual)
                        ProcessingCoordinator.check(job.sessionId, isStopped || !allowed || current == null || current.state == QueueState.DELETING)
                    }
                    check()
                    service.processor(check) { operation -> synchronized(ProcessingCoordinator.publication) { check(); operation() } }
                        .process(job.sessionId)
                    synchronized(ProcessingCoordinator.publication) {
                        check()
                        repo.job(job.sessionId)?.let { repo.updateJob(it.copy(state = QueueState.READY,
                            phase = ProcessingPhase.READY, error = null, manual = false)) }
                    }
                } catch (error: Exception) {
                    synchronized(ProcessingCoordinator.publication) {
                        repo.job(job.sessionId)?.takeIf { it.state != QueueState.DELETING }?.let {
                            repo.updateJob(it.copy(state = if (error is java.util.concurrent.CancellationException) QueueState.QUEUED else QueueState.FAILED,
                                phase = if (error is java.util.concurrent.CancellationException) ProcessingPhase.QUEUED else ProcessingPhase.FAILED,
                                error = if (error is java.util.concurrent.CancellationException) null else error.message))
                        }
                    }
                    if (error is java.util.concurrent.CancellationException) return Result.retry()
                }
            }
            return Result.retry()
        } catch (_: Exception) { return Result.retry() }
        finally { ProcessingCoordinator.heavy.unlock() }
    }
    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("recording_processing", "Recording processing", android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = android.app.Notification.Builder(applicationContext, "recording_processing")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Creating movement data")
            .setContentText("Your recordings remain available in Performance.").setOngoing(true).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(407, notification,
            if (android.os.Build.VERSION.SDK_INT >= 35) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(407, notification)
    }
}
