package dk.lasse.karatecliprecorder.movement

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import dk.lasse.karatecliprecorder.training.SessionMovement
import java.io.File
import java.util.concurrent.Executor

/**
 * Thumbnail helper implementing the D1 thumbnail contract:
 * extracts a clean canonical event frame with no analytical overlay or burnt-in text.
 * Uses a byte-bounded memory cache (4 MB) and downsamples frames to thumbnail dimensions.
 */
object MovementThumbnailHelper {
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024 // 4 MB memory budget
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getCachedThumbnail(movement: SessionMovement): Bitmap? {
        val targetUs = movement.analysisFrameUs ?: movement.endUs
        val cacheKey = "${movement.movementId}_$targetUs"
        return cache.get(cacheKey)
    }

    fun loadThumbnailAsync(
        executor: Executor,
        videoFile: File?,
        movement: SessionMovement,
        targetWidth: Int = 144,
        targetHeight: Int = 192,
        callback: (Bitmap?) -> Unit,
    ) {
        val targetUs = movement.analysisFrameUs ?: movement.endUs
        val cacheKey = "${movement.movementId}_$targetUs"
        val cached = cache.get(cacheKey)
        if (cached != null) {
            callback(cached)
            return
        }

        if (videoFile == null || !videoFile.isFile) {
            callback(null)
            return
        }

        executor.execute {
            val bitmap = extractScaledFrame(videoFile, targetUs, targetWidth, targetHeight)
            if (bitmap != null) {
                cache.put(cacheKey, bitmap)
            }
            mainHandler.post {
                callback(bitmap)
            }
        }
    }

    fun getThumbnail(
        videoFile: File?,
        movement: SessionMovement,
        targetWidth: Int = 144,
        targetHeight: Int = 192,
    ): Bitmap? {
        if (videoFile == null || !videoFile.isFile) return null
        val targetUs = movement.analysisFrameUs ?: movement.endUs
        val cacheKey = "${movement.movementId}_$targetUs"
        cache.get(cacheKey)?.let { return it }

        val bitmap = extractScaledFrame(videoFile, targetUs, targetWidth, targetHeight)
        if (bitmap != null) {
            cache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    private fun extractScaledFrame(
        videoFile: File,
        targetUs: Long,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    targetUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight,
                )
            } else null

            if (scaled != null) {
                scaled
            } else {
                val full = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (full != null) {
                    val resized = Bitmap.createScaledBitmap(full, targetWidth, targetHeight, true)
                    if (resized != full) full.recycle()
                    resized
                } else null
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun clearCache() {
        cache.evictAll()
    }
}
