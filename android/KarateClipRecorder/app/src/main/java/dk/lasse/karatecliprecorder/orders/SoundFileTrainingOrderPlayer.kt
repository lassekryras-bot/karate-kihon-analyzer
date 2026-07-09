package dk.lasse.karatecliprecorder.orders

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

class SoundFileTrainingOrderPlayer(context: Context) : TrainingOrderPlayer {
    private val appContext = context.applicationContext
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
    private val loadedSoundIds = mutableSetOf<Int>()
    private var activeStreamId: Int? = null
    private var pendingOrder: TrainingOrder? = null

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == LOAD_SUCCESS) {
                loadedSoundIds.add(sampleId)
                val orderToPlay = pendingOrder
                    ?.takeIf { pendingOrder -> soundIdsByOrder[pendingOrder] == sampleId }
                    ?: return@setOnLoadCompleteListener
                pendingOrder = null
                playLoaded(orderToPlay, sampleId)
            } else {
                Log.w(TAG, "Failed to load order sound sample $sampleId with status $status")
            }
        }
        TrainingOrderCatalog.all.forEach { order ->
            val resourceName = order.soundResourceName ?: return@forEach
            val resourceId = appContext.resources.getIdentifier(resourceName, RAW_RESOURCE_TYPE, appContext.packageName)
            if (resourceId != 0) {
                soundIdsByOrder[order] = soundPool.load(appContext, resourceId, SOUND_PRIORITY)
            } else {
                Log.w(TAG, "Missing raw sound resource: $resourceName")
            }
        }
    }

    override fun play(order: TrainingOrder) {
        val soundId = soundIdsByOrder[order] ?: return
        if (soundId !in loadedSoundIds) {
            pendingOrder = order
            return
        }
        playLoaded(order, soundId)
    }

    private fun playLoaded(order: TrainingOrder, soundId: Int) {
        activeStreamId?.let(soundPool::stop)
        activeStreamId = soundPool.play(soundId, VOLUME, VOLUME, SOUND_PRIORITY, NO_LOOP, PLAYBACK_RATE)
            .takeIf { it != 0 }
        if (activeStreamId == null) {
            Log.w(TAG, "SoundPool did not start playback for ${order.name}")
        }
    }

    override fun stop() {
        activeStreamId?.let(soundPool::stop)
        activeStreamId = null
        pendingOrder = null
    }

    override fun release() {
        stop()
        soundPool.release()
        soundIdsByOrder.clear()
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
    }
}
