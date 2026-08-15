package dk.lasse.karatecliprecorder.orders

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

class SoundFileTrainingOrderPlayer(context: Context) : TrainingOrderPlayer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private val soundIdsByOrder = mutableMapOf<TrainingOrder, Int>()
    private val durationsMsByOrder = mutableMapOf<TrainingOrder, Long>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private var activeStreamId: Int? = null
    private var pendingOrder: TrainingOrder? = null
    private var pendingCompletion: (() -> Unit)? = null
    private var activeCompletion: Runnable? = null

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == LOAD_SUCCESS) {
                loadedSoundIds.add(sampleId)
                val orderToPlay = pendingOrder
                    ?.takeIf { pendingOrder -> soundIdsByOrder[pendingOrder] == sampleId }
                    ?: return@setOnLoadCompleteListener
                val completion = pendingCompletion
                pendingOrder = null
                pendingCompletion = null
                playLoaded(orderToPlay, sampleId, completion)
            } else {
                Log.w(TAG, "Failed to load order sound sample $sampleId with status $status")
            }
        }
        TrainingOrderCatalog.all.forEach { order ->
            val resourceName = order.soundResourceName ?: return@forEach
            val resourceId = appContext.resources.getIdentifier(resourceName, RAW_RESOURCE_TYPE, appContext.packageName)
            if (resourceId != 0) {
                soundIdsByOrder[order] = soundPool.load(appContext, resourceId, SOUND_PRIORITY)
                readRawDurationMs(resourceId)?.let { durationMs -> durationsMsByOrder[order] = durationMs }
            } else {
                Log.w(TAG, "Missing raw sound resource: $resourceName")
            }
        }
    }

    override fun play(order: TrainingOrder, onComplete: (() -> Unit)?) {
        cancelCompletionCallback()
        pendingOrder = null
        pendingCompletion = null
        val soundId = soundIdsByOrder[order]
        if (soundId == null) {
            onComplete?.invoke()
            return
        }
        if (soundId !in loadedSoundIds) {
            pendingOrder = order
            pendingCompletion = onComplete
            return
        }
        playLoaded(order, soundId, onComplete)
    }

    private fun playLoaded(order: TrainingOrder, soundId: Int, onComplete: (() -> Unit)?) {
        cancelCompletionCallback()
        activeStreamId?.let(soundPool::stop)
        activeStreamId = soundPool.play(soundId, VOLUME, VOLUME, SOUND_PRIORITY, NO_LOOP, PLAYBACK_RATE)
            .takeIf { it != 0 }
        if (activeStreamId == null) {
            Log.w(TAG, "SoundPool did not start playback for ${order.name}")
            onComplete?.invoke()
        } else {
            scheduleCompletionCallback(order, onComplete)
        }
    }

    override fun stop() {
        cancelCompletionCallback()
        activeStreamId?.let(soundPool::stop)
        activeStreamId = null
        pendingOrder = null
        pendingCompletion = null
    }

    override fun release() {
        stop()
        soundPool.release()
        soundIdsByOrder.clear()
        durationsMsByOrder.clear()
    }

    private fun scheduleCompletionCallback(order: TrainingOrder, onComplete: (() -> Unit)?) {
        if (onComplete == null) return
        val completion = Runnable {
            activeCompletion = null
            activeStreamId = null
            onComplete()
        }
        activeCompletion = completion
        val durationMs = durationsMsByOrder[order] ?: DEFAULT_SOUND_DURATION_MS
        mainHandler.postDelayed(completion, durationMs + COMPLETION_BUFFER_MS)
    }

    private fun cancelCompletionCallback() {
        activeCompletion?.let(mainHandler::removeCallbacks)
        activeCompletion = null
    }

    private fun readRawDurationMs(resourceId: Int): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse("android.resource://${appContext.packageName}/$resourceId")
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to read sound duration for resource $resourceId", error)
            null
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val TAG = "TrainingOrderAudio"
        const val MAX_STREAMS = 1
        const val RAW_RESOURCE_TYPE = "raw"
        const val SOUND_PRIORITY = 1
        const val LOAD_SUCCESS = 0
        const val VOLUME = 1.0f
        const val NO_LOOP = 0
        const val PLAYBACK_RATE = 1.0f
        const val DEFAULT_SOUND_DURATION_MS = 1_000L
        const val COMPLETION_BUFFER_MS = 80L
    }
}
