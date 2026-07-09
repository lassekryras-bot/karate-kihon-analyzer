package dk.lasse.karatecliprecorder.orders

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class SoundFileTrainingOrderPlayer(context: Context) : TrainingOrderPlayer {
    private val appContext = context.applicationContext
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private val soundIdsByOrder = mutableMapOf<TrainingOrder, Int>()
    private var activeStreamId: Int? = null

    init {
        TrainingOrderCatalog.all.forEach { order ->
            val resourceName = order.soundResourceName ?: return@forEach
            val resourceId = appContext.resources.getIdentifier(resourceName, RAW_RESOURCE_TYPE, appContext.packageName)
            if (resourceId != 0) {
                soundIdsByOrder[order] = soundPool.load(appContext, resourceId, SOUND_PRIORITY)
            }
        }
    }

    override fun play(order: TrainingOrder) {
        val soundId = soundIdsByOrder[order] ?: return
        activeStreamId?.let(soundPool::stop)
        activeStreamId = soundPool.play(soundId, VOLUME, VOLUME, SOUND_PRIORITY, NO_LOOP, PLAYBACK_RATE)
            .takeIf { it != 0 }
    }

    override fun stop() {
        activeStreamId?.let(soundPool::stop)
        activeStreamId = null
    }

    override fun release() {
        stop()
        soundPool.release()
        soundIdsByOrder.clear()
    }

    private companion object {
        const val MAX_STREAMS = 1
        const val RAW_RESOURCE_TYPE = "raw"
        const val SOUND_PRIORITY = 1
        const val VOLUME = 1.0f
        const val NO_LOOP = 0
        const val PLAYBACK_RATE = 1.0f
    }
}
