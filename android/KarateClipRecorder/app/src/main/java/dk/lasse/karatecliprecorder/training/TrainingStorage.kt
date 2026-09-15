package dk.lasse.karatecliprecorder.training

import java.io.File
import java.util.UUID
import dk.lasse.karatecliprecorder.sharedcapture.CaptureType

/** References are relative to files/training. Absolute v1 references remain readable. */
class TrainingStorage(val root: File) {
    fun recording(id: String) = reference("recordings", id, "mp4")
    fun photo(id: String) = reference("photos", id, "jpg")
    fun capture(id: String, type: CaptureType) = if (type == CaptureType.VIDEO) recording(id) else photo(id)
    fun landmarks(id: String) = reference("landmarks", id, "mls")
    private fun reference(directory: String, id: String, extension: String): String {
        require(UUID.fromString(id).toString() == id)
        return "$directory/$id.$extension"
    }
    fun resolve(reference: String): File {
        val old = File(reference)
        if (old.isAbsolute) return old // Explicit compatibility with pre-MLS recordings.
        val target = File(root, reference).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath()) && target != root.canonicalFile)
        return target
    }
    fun reference(file: File): String {
        val path = file.canonicalFile.toPath()
        require(path.startsWith(root.canonicalFile.toPath()))
        return root.canonicalFile.toPath().relativize(path).toString().replace('\\', '/')
    }
}
