package dk.lasse.karatecliprecorder

import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.ByteBuffer

object CameraRgbaBitmapConverter {
    fun convert(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        rotationDegrees: Int,
    ): Bitmap? {
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride < width * pixelStride) return null
        return runCatching {
            val packed = ByteArray(width * height * 4)
            val duplicate = buffer.duplicate()
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    duplicate.position(rowStart + col * pixelStride)
                    val outputIndex = (row * width + col) * 4
                    duplicate.get(packed, outputIndex, 4)
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            bitmap.rotate(rotationDegrees)
        }.getOrNull()
    }

    private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        recycle()
        return rotated
    }
}
