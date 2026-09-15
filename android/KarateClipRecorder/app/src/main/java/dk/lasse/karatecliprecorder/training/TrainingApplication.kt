package dk.lasse.karatecliprecorder.training

import android.app.Activity
import android.app.Application
import android.os.Bundle

class TrainingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        dk.lasse.karatecliprecorder.sharedcapture.CaptureFinalizedEvents.listener = {
            if (it.captureType == dk.lasse.karatecliprecorder.sharedcapture.CaptureType.VIDEO) RecordingQueue.schedule(this)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (ProcessingCoordinator.foregroundActivities.incrementAndGet() == 1) RecordingQueue.schedule(this@TrainingApplication)
            }
            override fun onActivityStopped(activity: Activity) { ProcessingCoordinator.foregroundActivities.decrementAndGet() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
