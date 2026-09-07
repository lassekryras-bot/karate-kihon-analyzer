package dk.lasse.karatecliprecorder.learning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PunchHeightCaptureSnapshot
import dk.lasse.karateanalyzer.core.PunchHeightTargetType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

data class PunchHeightSavedCapture(
    val targetType: PunchHeightTargetType,
    val originalFile: File,
    val analysisFile: File,
    val snapshot: PunchHeightCaptureSnapshot,
)

data class PunchHeightCompletedSession(
    val directory: File,
    val captures: List<PunchHeightSavedCapture>,
    val metadataFile: File,
)

class PunchHeightLatestPublisher {
    fun recover(latest: File) {
        val backup = File(latest.parentFile, ".latest-backup")
        if (!latest.exists() && backup.exists()) {
            check(backup.renameTo(latest)) { "Could not restore the previous completed session." }
        } else if (latest.exists() && backup.exists()) {
            backup.deleteRecursively()
        }
    }

    fun publish(staging: File, latest: File): File {
        val backup = File(latest.parentFile, ".latest-backup")
        if (backup.exists() && !backup.deleteRecursively()) error("Could not remove an old session backup.")
        if (latest.exists() && !latest.renameTo(backup)) error("Could not preserve the previous completed session.")
        if (!staging.renameTo(latest)) {
            if (backup.exists()) backup.renameTo(latest)
            error("Could not publish the completed Punch Heights session.")
        }
        if (backup.exists()) backup.deleteRecursively()
        return latest
    }
}

class PunchHeightCaptureStore(
    context: Context,
    private val latestPublisher: PunchHeightLatestPublisher = PunchHeightLatestPublisher(),
) {
    private val picturesRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
    private var featureRoot = File(picturesRoot, "punch_height_level_1/legacy")
    private var stagingDirectory: File? = null
    private val captures = linkedMapOf<PunchHeightTargetType, PunchHeightSavedCapture>()

    @Synchronized fun beginSession(profileId: String = "legacy") {
        cancelSession()
        featureRoot = File(picturesRoot, "punch_height_level_1/profiles/$profileId")
        check(featureRoot.exists() || featureRoot.mkdirs()) { "Could not create the Punch Heights picture directory." }
        latestPublisher.recover(File(featureRoot, "latest"))
        featureRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(".staging-") }
            .forEach(File::deleteRecursively)
        val staging = File(featureRoot, ".staging-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Could not create the session staging directory." }
        stagingDirectory = staging
        captures.clear()
    }

    @Synchronized fun saveCapture(
        acceptedFrame: Bitmap,
        snapshot: PunchHeightCaptureSnapshot,
    ): PunchHeightSavedCapture {
        try {
            val staging = stagingDirectory ?: error("Punch Heights session has not been started.")
            val baseName = snapshot.targetType.name.lowercase(Locale.ROOT)
            val original = File(staging, "$baseName-original.jpg")
            val analysis = File(staging, "$baseName-analysis.jpg")
            writeJpeg(acceptedFrame, original)
            val annotated = PunchHeightImageRenderer.render(acceptedFrame, snapshot)
            try {
                writeJpeg(annotated, analysis)
            } finally {
                if (!annotated.isRecycled) annotated.recycle()
            }
            val saved = PunchHeightSavedCapture(snapshot.targetType, original, analysis, snapshot)
            captures[snapshot.targetType] = saved
            return saved
        } finally {
            if (!acceptedFrame.isRecycled) acceptedFrame.recycle()
        }
    }

    @Synchronized fun completeSession(): PunchHeightCompletedSession {
        val staging = stagingDirectory ?: error("Punch Heights session has not been started.")
        val ordered = PunchHeightTargetType.entries.map { target ->
            captures[target] ?: error("Missing ${target.name.lowercase()} capture.")
        }
        val metadata = File(staging, "session.json")
        metadata.writeText(sessionJson(ordered), Charsets.UTF_8)

        val latest = File(featureRoot, "latest")
        latestPublisher.publish(staging, latest)
        stagingDirectory = null
        val publishedCaptures = ordered.map { saved ->
            saved.copy(
                originalFile = File(latest, saved.originalFile.name),
                analysisFile = File(latest, saved.analysisFile.name),
            )
        }
        captures.clear()
        return PunchHeightCompletedSession(latest, publishedCaptures, File(latest, metadata.name))
    }

    @Synchronized fun cancelSession() {
        stagingDirectory?.takeIf(File::exists)?.deleteRecursively()
        stagingDirectory = null
        captures.clear()
    }

    fun latestDirectory(): File = File(featureRoot, "latest")

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "JPEG encoding failed for ${file.name}." }
            output.fd.sync()
        }
    }

    private fun sessionJson(saved: List<PunchHeightSavedCapture>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"model\": {\"asset\": \"mediapipe/pose_landmarker_full.task\", \"configurationVersion\": \"punch-height-level-1-v1\"},\n")
        append("  \"configuration\": {\"detectionConfidence\": 0.5, \"presenceConfidence\": 0.5, \"trackingConfidence\": 0.5, \"captureReliability\": 0.55, \"holdMs\": 1200},\n")
        append("  \"captures\": [\n")
        saved.forEachIndexed { index, capture ->
            val snapshot = capture.snapshot
            append("    {\n")
            append("      \"target\": \"${snapshot.targetType.name.lowercase(Locale.ROOT)}\",\n")
            append("      \"timestampMs\": ${snapshot.timestampMs},\n")
            append("      \"original\": \"${capture.originalFile.name}\",\n")
            append("      \"analysis\": \"${capture.analysisFile.name}\",\n")
            append("      \"visibleSide\": \"${snapshot.bodyReference.visibleSide.name.lowercase(Locale.ROOT)}\",\n")
            append("      \"activeArm\": \"${snapshot.activeArm.name.lowercase(Locale.ROOT)}\",\n")
            append("      \"torsoLength\": ${snapshot.bodyReference.torsoLength.jsonFloat()},\n")
            append("      \"targetScalar\": ${snapshot.target.torsoScalar.jsonFloat()},\n")
            append("      \"tolerance\": ${snapshot.target.tolerance.jsonFloat()},\n")
            append("      \"targetConfidence\": ${snapshot.target.confidence.jsonFloat()},\n")
            append("      \"targetStrategy\": \"${snapshot.target.calculationStrategy.jsonEscape()}\",\n")
            append("      \"targetPoint\": ${snapshot.target.targetPoint.jsonPoint()},\n")
            append("      \"fistCenter\": ${snapshot.fistCenter.jsonPoint()},\n")
            append("      \"shoulderPoint\": ${snapshot.shoulderPoint.jsonPoint()},\n")
            append("      \"elbowPoint\": ${snapshot.elbowPoint.jsonPoint()},\n")
            append("      \"wristPoint\": ${snapshot.wristPoint.jsonPoint()},\n")
            append("      \"bodyShoulderReference\": ${snapshot.bodyReference.shoulderPoint.jsonPoint()},\n")
            append("      \"bodyHipReference\": ${snapshot.bodyReference.hipPoint.jsonPoint()},\n")
            append("      \"torsoAxis\": ${snapshot.bodyReference.torsoAxis.jsonPoint()},\n")
            append("      \"signedErrorTorsoRatio\": ${snapshot.signedHeightErrorTorsoRatio.jsonFloat()},\n")
            append("      \"elbowAngleDegrees\": ${snapshot.elbowAngleDegrees.jsonFloat()},\n")
            append("      \"holdDurationMs\": ${snapshot.stableHoldMs},\n")
            append("      \"chinProjectionMultiplier\": ${snapshot.chinProjectionMultiplier.jsonFloat()},\n")
            append("      \"chinSource\": \"${snapshot.target.chinEstimate?.source?.name?.lowercase(Locale.ROOT) ?: "not-applicable"}\",\n")
            append("      \"rawChin\": ${snapshot.target.chinEstimate?.rawPoint?.jsonPoint() ?: "null"},\n")
            append("      \"smoothedChin\": ${snapshot.target.chinEstimate?.smoothedPoint?.jsonPoint() ?: "null"},\n")
            append("      \"accepted\": true\n")
            append("    }${if (index == saved.lastIndex) "" else ","}\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun Float.jsonFloat() = String.format(Locale.US, "%.6f", this)
    private fun Point3.jsonPoint() = "{\"x\":${x.jsonFloat()},\"y\":${y.jsonFloat()},\"z\":${z.jsonFloat()}}"
    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

object PunchHeightImageRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun render(original: Bitmap, snapshot: PunchHeightCaptureSnapshot): Bitmap {
        val output = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val torso = snapshot.bodyReference
        val axis = torso.torsoAxis
        val perpendicular = normalized(Point3(-axis.y, axis.x, 0f))
        val halfLength = torso.torsoLength * 0.42f
        val toleranceOffset = axis * (torso.torsoLength * snapshot.target.tolerance)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(3f, output.width / 180f)
        paint.color = CYAN
        listOf(snapshot.target.targetPoint - toleranceOffset, snapshot.target.targetPoint + toleranceOffset).forEach { edge ->
            canvas.drawLine(
                (edge.x - perpendicular.x * halfLength) * output.width,
                (edge.y - perpendicular.y * halfLength) * output.height,
                (edge.x + perpendicular.x * halfLength) * output.width,
                (edge.y + perpendicular.y * halfLength) * output.height,
                paint,
            )
        }
        paint.color = YELLOW
        canvas.drawLine(
            torso.shoulderPoint.x * output.width,
            torso.shoulderPoint.y * output.height,
            torso.hipPoint.x * output.width,
            torso.hipPoint.y * output.height,
            paint,
        )
        paint.color = GREEN
        canvas.drawLine(snapshot.shoulderPoint.x * output.width, snapshot.shoulderPoint.y * output.height, snapshot.elbowPoint.x * output.width, snapshot.elbowPoint.y * output.height, paint)
        canvas.drawLine(snapshot.elbowPoint.x * output.width, snapshot.elbowPoint.y * output.height, snapshot.wristPoint.x * output.width, snapshot.wristPoint.y * output.height, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(snapshot.fistCenter.x * output.width, snapshot.fistCenter.y * output.height, output.width / 45f, paint)
        canvas.drawCircle(torso.shoulderPoint.x * output.width, torso.shoulderPoint.y * output.height, output.width / 80f, paint)
        canvas.drawCircle(torso.hipPoint.x * output.width, torso.hipPoint.y * output.height, output.width / 80f, paint)

        if (snapshot.targetType == PunchHeightTargetType.JODAN) {
            snapshot.target.chinEstimate?.rawPoint?.let { point ->
                paint.style = Paint.Style.STROKE
                paint.color = RED
                canvas.drawCircle(point.x * output.width, point.y * output.height, output.width / 65f, paint)
            }
            snapshot.target.chinEstimate?.smoothedPoint?.let { point ->
                paint.color = GREEN
                canvas.drawCircle(point.x * output.width, point.y * output.height, output.width / 55f, paint)
            }
        }

        val textSize = maxOf(17f, output.width / 27f)
        val lines = buildList {
            add("${snapshot.targetType.name} — ACCEPTED")
            add("Error ${String.format(Locale.US, "%+.1f", snapshot.signedHeightErrorTorsoRatio * 100f)}% torso")
            add("${snapshot.activeArm.name.lowercase().replaceFirstChar(Char::uppercase)} arm • elbow ${String.format(Locale.US, "%.1f°", snapshot.elbowAngleDegrees)}")
            add("Confidence ${String.format(Locale.US, "%.2f", snapshot.target.confidence)} • hold ${snapshot.stableHoldMs} ms")
            if (snapshot.targetType == PunchHeightTargetType.JODAN) {
                add("Chin ×${String.format(Locale.US, "%.2f", snapshot.chinProjectionMultiplier)} • ${snapshot.target.chinEstimate?.source?.name ?: "LOST"}")
            }
        }
        paint.textSize = textSize
        paint.style = Paint.Style.FILL
        val lineHeight = textSize * 1.3f
        paint.color = 0xbb000000.toInt()
        canvas.drawRect(0f, 0f, output.width.toFloat(), lineHeight * lines.size + textSize * 0.5f, paint)
        paint.color = Color.WHITE
        lines.forEachIndexed { index, line -> canvas.drawText(line, textSize * 0.45f, lineHeight * (index + 1), paint) }
        return output
    }

    private fun normalized(point: Point3): Point3 {
        val length = sqrt(point.x * point.x + point.y * point.y).coerceAtLeast(0.0001f)
        return Point3(point.x / length, point.y / length, 0f)
    }

    private const val RED = 0xffff5252.toInt()
    private const val YELLOW = 0xffffeb3b.toInt()
    private const val GREEN = 0xff4caf50.toInt()
    private const val CYAN = 0xff00e5ff.toInt()
}
