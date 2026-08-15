package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.media.MediaPlayer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Plays the ten prerecorded count cues as one continuous WAV recording. */
class JapaneseCountFullExamplePlayer(context: Context) {
    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null

    fun play(
        onComplete: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        stop()
        val recording = try {
            assembledRecording()
        } catch (error: Throwable) {
            onError?.invoke(error)
            return
        }

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(recording.absolutePath)
            player.setOnCompletionListener {
                if (mediaPlayer === player) mediaPlayer = null
                player.release()
                onComplete?.invoke()
            }
            player.setOnErrorListener { _, what, extra ->
                if (mediaPlayer === player) mediaPlayer = null
                player.release()
                onError?.invoke(
                    IOException("Full count playback failed (what=$what, extra=$extra)."),
                )
                true
            }
            player.prepare()
            player.start()
        } catch (error: Throwable) {
            if (mediaPlayer === player) mediaPlayer = null
            runCatching { player.release() }
            onError?.invoke(error)
        }
    }

    fun stop() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    fun release() = stop()

    private fun assembledRecording(): File {
        val output = File(appContext.cacheDir, OUTPUT_FILE_NAME)
        if (isValidCachedRecording(output)) return output
        runCatching { output.delete() }

        val clips = JapaneseCountLesson.items.map { item ->
            val resourceName = requireNotNull(item.order.soundResourceName)
            val resourceId = appContext.resources.getIdentifier(
                resourceName,
                RAW_RESOURCE_TYPE,
                appContext.packageName,
            )
            if (resourceId == 0) throw IOException("Missing count audio resource: $resourceName")
            appContext.resources.openRawResource(resourceId).use { input ->
                parsePcmWav(input.readBytes())
            }
        }
        val format = clips.firstOrNull()?.format ?: throw IOException("No count audio clips were found.")
        if (clips.any { it.format != format }) {
            throw IOException("Count audio clips do not share one PCM format.")
        }

        val silenceSize = (
            format.sampleRateHz *
                format.channelCount *
                format.bitsPerSample / BITS_PER_BYTE *
                SILENCE_BETWEEN_COUNTS_MS / MILLIS_PER_SECOND
            )
        val pcm = ByteArrayOutputStream()
        clips.forEachIndexed { index, clip ->
            if (index > 0) pcm.write(ByteArray(silenceSize))
            pcm.write(clip.pcm)
        }
        val pcmBytes = pcm.toByteArray()
        val temporary = File.createTempFile(OUTPUT_FILE_PREFIX, OUTPUT_FILE_SUFFIX, appContext.cacheDir)
        try {
            temporary.outputStream().buffered().use { stream ->
                stream.write(wavHeader(format, pcmBytes.size))
                stream.write(pcmBytes)
            }
            val written = parsePcmWav(temporary.readBytes())
            if (written.format != format || written.pcm.size != pcmBytes.size) {
                throw IOException("The assembled full count recording failed validation.")
            }
            if (!temporary.renameTo(output)) {
                throw IOException("Could not publish the assembled full count recording.")
            }
        } finally {
            runCatching { temporary.delete() }
        }
        return output
    }

    private fun isValidCachedRecording(file: File): Boolean {
        if (!file.isFile || file.length() <= WAV_HEADER_BYTES) return false
        return runCatching {
            val parsed = parsePcmWav(file.readBytes())
            parsed.pcm.isNotEmpty() &&
                parsed.format.channelCount > 0 &&
                parsed.format.sampleRateHz > 0 &&
                parsed.format.bitsPerSample > 0
        }.getOrDefault(false)
    }

    private fun parsePcmWav(bytes: ByteArray): PcmWav {
        if (
            bytes.size < WAV_HEADER_BYTES ||
            bytes.ascii(0, 4) != "RIFF" ||
            bytes.ascii(8, 4) != "WAVE"
        ) {
            throw IOException("Count audio is not a RIFF/WAVE file.")
        }

        var format: PcmFormat? = null
        var pcm: ByteArray? = null
        var offset = RIFF_HEADER_BYTES
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val chunkName = bytes.ascii(offset, 4)
            val chunkSize = bytes.intLittleEndian(offset + 4)
            val dataOffset = offset + CHUNK_HEADER_BYTES
            if (chunkSize < 0 || dataOffset + chunkSize > bytes.size) break
            when (chunkName) {
                "fmt " -> {
                    if (chunkSize < PCM_FORMAT_CHUNK_BYTES) throw IOException("Invalid WAV format chunk.")
                    val audioFormat = bytes.unsignedShortLittleEndian(dataOffset)
                    if (audioFormat != PCM_AUDIO_FORMAT) throw IOException("Count audio must use integer PCM.")
                    format = PcmFormat(
                        channelCount = bytes.unsignedShortLittleEndian(dataOffset + 2),
                        sampleRateHz = bytes.intLittleEndian(dataOffset + 4),
                        bitsPerSample = bytes.unsignedShortLittleEndian(dataOffset + 14),
                    )
                }
                "data" -> pcm = bytes.copyOfRange(dataOffset, dataOffset + chunkSize)
            }
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }
        return PcmWav(
            format = format ?: throw IOException("WAV format chunk is missing."),
            pcm = pcm ?: throw IOException("WAV audio data is missing."),
        )
    }

    private fun wavHeader(format: PcmFormat, dataSize: Int): ByteArray {
        val bytesPerSample = format.bitsPerSample / BITS_PER_BYTE
        val byteRate = format.sampleRateHz * format.channelCount * bytesPerSample
        val blockAlign = format.channelCount * bytesPerSample
        return ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(StandardCharsets.US_ASCII))
                putInt(dataSize + WAV_HEADER_BYTES - RIFF_SIZE_FIELD_BYTES)
                put("WAVE".toByteArray(StandardCharsets.US_ASCII))
                put("fmt ".toByteArray(StandardCharsets.US_ASCII))
                putInt(PCM_FORMAT_CHUNK_BYTES)
                putShort(PCM_AUDIO_FORMAT.toShort())
                putShort(format.channelCount.toShort())
                putInt(format.sampleRateHz)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(format.bitsPerSample.toShort())
                put("data".toByteArray(StandardCharsets.US_ASCII))
                putInt(dataSize)
            }
            .array()
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, StandardCharsets.US_ASCII)

    private fun ByteArray.intLittleEndian(offset: Int): Int =
        ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.unsignedShortLittleEndian(offset: Int): Int =
        ByteBuffer.wrap(this, offset, Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private data class PcmWav(
        val format: PcmFormat,
        val pcm: ByteArray,
    )

    private data class PcmFormat(
        val channelCount: Int,
        val sampleRateHz: Int,
        val bitsPerSample: Int,
    )

    private companion object {
        // Bump when a packaged count cue changes so installed apps do not replay a stale assembly.
        const val OUTPUT_FILE_NAME = "japanese-count-full-example-v3-old-ku.wav"
        const val OUTPUT_FILE_PREFIX = "japanese-count-full-example-"
        const val OUTPUT_FILE_SUFFIX = ".tmp"
        const val RAW_RESOURCE_TYPE = "raw"
        const val PCM_AUDIO_FORMAT = 1
        const val PCM_FORMAT_CHUNK_BYTES = 16
        const val WAV_HEADER_BYTES = 44
        const val RIFF_HEADER_BYTES = 12
        const val CHUNK_HEADER_BYTES = 8
        const val RIFF_SIZE_FIELD_BYTES = 8
        const val BITS_PER_BYTE = 8
        const val SILENCE_BETWEEN_COUNTS_MS = 180
        const val MILLIS_PER_SECOND = 1_000
    }
}
