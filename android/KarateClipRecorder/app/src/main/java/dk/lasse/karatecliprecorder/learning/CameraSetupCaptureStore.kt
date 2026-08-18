package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import dk.lasse.karateanalyzer.core.PoseFrame
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class CameraSetupCapture(
    val imageFile: File,
    val originalFile: File,
    val metadataFile: File,
    val view: CameraView,
)

class CameraSetupCaptureStore(context: Context) {
    private val picturesRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir

    @Synchronized fun save(bitmap: Bitmap, view: CameraView, poseFrame: PoseFrame, profileId: String): CameraSetupCapture {
        val outputDirectory = File(picturesRoot, "camera_setup/profiles/$profileId/latest")
        try {
            check(outputDirectory.exists() || outputDirectory.mkdirs()) { "Could not create the camera setup picture directory." }
            val baseName = view.name.lowercase(Locale.ROOT)
            val original = File(outputDirectory, "$baseName-original.jpg")
            val image = File(outputDirectory, "$baseName-punch-heights.jpg")
            writeJpeg(bitmap, original)
            val annotated = CameraSetupImageRenderer.render(bitmap, poseFrame)
            try {
                writeJpeg(annotated, image)
            } finally {
                if (!annotated.isRecycled) annotated.recycle()
            }
            val metadata = File(outputDirectory, "$baseName.json")
            metadata.writeText(
                """{"schemaVersion":2,"view":"$baseName","original":"${original.name}","image":"${image.name}","overlays":["jodan","chudan","gedan"],"capturedAtEpochMs":${System.currentTimeMillis()}}""",
                Charsets.UTF_8,
            )
            return CameraSetupCapture(image, original, metadata, view)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "Could not encode ${target.name}." }
            output.fd.sync()
        }
        if (target.exists()) check(target.delete()) { "Could not replace ${target.name}." }
        check(temporary.renameTo(target)) { "Could not publish ${target.name}." }
    }
}

object CameraSetupImageRenderer {
    private val colors = intArrayOf(0xffff5252.toInt(), 0xffffc107.toInt(), 0xff29b6f6.toInt())

    fun render(original: Bitmap, poseFrame: PoseFrame): Bitmap {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = maxOf(5f, output.width / 150f)
            strokeCap = Paint.Cap.ROUND
            textSize = maxOf(28f, output.width / 25f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        CameraSetupPunchGuides.calculate(poseFrame).forEachIndexed { index, guide ->
            paint.color = colors[index % colors.size]
            paint.style = Paint.Style.STROKE
            canvas.drawLine(
                guide.leftX * output.width,
                guide.y * output.height,
                guide.rightX * output.width,
                guide.y * output.height,
                paint,
            )
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(4f, 1f, 1f, Color.BLACK)
            canvas.drawText(
                guide.label,
                guide.leftX * output.width,
                guide.y * output.height - paint.textSize * 0.25f,
                paint,
            )
            paint.clearShadowLayer()
        }
        return output
    }
}
