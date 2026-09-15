package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import java.io.*
import java.security.MessageDigest

/** Versioned dense-file format. Time is recording-relative us; source adapter currently resolves to ms. */
object LandmarkFiles {
    const val FORMAT_ID = "movement_landmark_stream_v1"
    const val VERSION = 1
    const val LEGACY_FORMAT_ID = "karate_pose_track_v1"
    private const val INTERPRETATION = "time=recording-relative-us;resolution=ms;image=normalized-upright-unmirrored;world=meters-hip-origin;identity=PoseLandmarkId;source=LandmarkSource"
    fun write(file: File, frames: List<PoseFrame>, checkActive: () -> Unit = {},
              publication: (() -> Unit) -> Unit = { it() }): String {
        checkActive()
        require(frames.isNotEmpty())
        require(frames.first().timestampMs >= 0 && frames.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false)
        check(!file.exists()) { "Landmark filenames cannot be reused" }
        val staging = File(file.path + ".tmp")
        lateinit var opened: FileOutputStream
        publication {
            checkActive()
            check(staging.createNewFile()) { "Stale landmark staging file; retry with a new track UUID" }
            opened = FileOutputStream(staging)
        }
        opened.use { stream ->
            val output = DataOutputStream(BufferedOutputStream(stream))
            output.writeUTF(FORMAT_ID)
            output.writeInt(VERSION)
            output.writeUTF(INTERPRETATION)
            output.writeInt(frames.size)
            frames.forEach { frame ->
                checkActive()
                output.writeLong(Math.multiplyExact(frame.timestampMs, 1000L))
                output.writeInt(frame.landmarks.size)
                frame.landmarks.forEach { (id, sample) ->
                    output.writeUTF(id.name)
                    output.point(sample.position)
                    output.point(sample.worldPosition)
                    output.writeFloat(sample.visibility)
                    output.writeFloat(sample.presence)
                    output.writeUTF(sample.source.name)
                }
            }
            output.flush()
            stream.fd.sync()
        }
        readInternal(staging, allowStaging = true)
        val hash = sha256(staging)
        publication { checkActive(); check(!file.exists() && staging.renameTo(file)) { "Could not publish landmark track" } }
        return hash
    }

    fun read(file: File, expectedHash: String? = null, expectedFormatId: String? = null): List<PoseFrame> {
        return readInternal(file, expectedHash, expectedFormatId = expectedFormatId)
    }

    private fun readInternal(file: File, expectedHash: String? = null, allowStaging: Boolean = false,
                             expectedFormatId: String? = null): List<PoseFrame> {
        check(allowStaging || !file.name.endsWith(".tmp") && !file.name.endsWith(".pending")) { "Unpublished landmark track" }
        if (expectedHash != null) check(sha256(file) == expectedHash) { "Landmark checksum mismatch" }
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val format = input.readUTF()
            check(expectedFormatId == null || expectedFormatId == format) { "Landmark format does not match Room metadata" }
            when (format) {
                FORMAT_ID -> {
                    check(input.readInt() == VERSION) { "Unsupported MLS version" }
                    check(input.readUTF() == INTERPRETATION) { "Unsupported MLS interpretation" }
                }
                LEGACY_FORMAT_ID -> Unit // Separate v1 header path, not an MLS alias.
                else -> error("Unsupported landmark file version")
            }
            val count = input.readInt()
            check(count > 0 && count.toLong() <= file.length() / 12)
            var previous = -1L
            val frames = List(count) {
                val timeUs = input.readLong()
                check(timeUs >= 0 && timeUs > previous && timeUs % 1000L == 0L)
                previous = timeUs
                val landmarks = input.readInt()
                check(landmarks in 0..PoseLandmarkId.entries.size)
                val samples = List(landmarks) {
                    PoseLandmarkId.valueOf(input.readUTF()) to PoseLandmarkSample(
                        input.point(), input.point(), input.readFloat(), input.readFloat(),
                        LandmarkSource.valueOf(input.readUTF())).also { sample ->
                        check(sample.visibility.isFinite() && sample.visibility in 0f..1f)
                        check(sample.presence.isFinite() && sample.presence in 0f..1f)
                        listOfNotNull(sample.position, sample.worldPosition).forEach {
                            check(it.x.isFinite() && it.y.isFinite() && it.z.isFinite())
                        }
                    }
                }.toMap()
                check(samples.size == landmarks)
                PoseFrame(timeUs / 1000L, samples)
            }
            check(input.read() == -1) { "Trailing landmark file data" }
            frames
        }
    }

    fun sha256(file: File): String = file.inputStream().use(::sha256)
    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun DataOutputStream.point(point: Point3?) {
        writeBoolean(point != null)
        point?.let { writeFloat(it.x); writeFloat(it.y); writeFloat(it.z) }
    }
    private fun DataInputStream.point(): Point3? = if (readBoolean()) Point3(readFloat(), readFloat(), readFloat()) else null
}
