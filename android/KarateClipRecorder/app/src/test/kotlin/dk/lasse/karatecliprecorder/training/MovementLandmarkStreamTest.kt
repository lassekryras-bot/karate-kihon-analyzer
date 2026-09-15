package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import java.io.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MovementLandmarkStreamTest {
    @get:Rule val directory = TemporaryFolder()
    private val frames = listOf(PoseFrame(0, mapOf(PoseLandmarkId.LEFT_WRIST to
        PoseLandmarkSample(Point3(.1f, .2f, -.3f), Point3(-1f, 2f, 3f), .75f, .9f, LandmarkSource.OBSERVED))),
        PoseFrame(33, emptyMap()))

    @Test fun roundTripPreservesAllFieldsAndExplicitMlsHeader() {
        val file = File(directory.root, "${trainingId()}.mls")
        val hash = LandmarkFiles.write(file, frames)
        DataInputStream(file.inputStream()).use {
            assertEquals("movement_landmark_stream_v1", it.readUTF())
            assertEquals(1, it.readInt())
        }
        assertEquals(frames, LandmarkFiles.read(file, hash))
        assertEquals(64, hash.length)
        assertFails { LandmarkFiles.write(file, frames) }
        assertFails { LandmarkFiles.read(file, "wrong-checksum") }
    }

    @Test fun independentLegacyFixtureUsesOldHeaderAndFullPayload() {
        val file = File(directory.root, "legacy.pose")
        DataOutputStream(file.outputStream()).use {
            it.writeUTF("karate_pose_track_v1"); it.writeInt(1)
            it.writeLong(33_000); it.writeInt(1); it.writeUTF("LEFT_WRIST")
            it.writeBoolean(true); it.writeFloat(.1f); it.writeFloat(.2f); it.writeFloat(-.3f)
            it.writeBoolean(true); it.writeFloat(-1f); it.writeFloat(2f); it.writeFloat(3f)
            it.writeFloat(.75f); it.writeFloat(.9f); it.writeUTF("OBSERVED")
        }
        assertEquals(listOf(frames.first().copy(timestampMs = 33)), LandmarkFiles.read(file))
        assertFails { LandmarkFiles.read(file, expectedFormatId = LandmarkFiles.FORMAT_ID) }
    }

    @Test fun unsupportedFormatVersionAndIncompleteStreamAreRejected() {
        val file = File(directory.root, "invalid.mls")
        DataOutputStream(file.outputStream()).use { it.writeUTF("unknown_landmarks_v8") }
        assertFails { LandmarkFiles.read(file) }
        DataOutputStream(file.outputStream()).use { it.writeUTF(LandmarkFiles.FORMAT_ID); it.writeInt(2) }
        assertFails { LandmarkFiles.read(file) }
        DataOutputStream(file.outputStream()).use { it.writeUTF(LandmarkFiles.LEGACY_FORMAT_ID); it.writeInt(5); it.writeLong(0) }
        assertFails { LandmarkFiles.read(file) }
    }

    @Test fun stagingIsNotEvidenceEvenIfItContainsCompleteBytes() {
        val file = File(directory.root, "${trainingId()}.mls")
        LandmarkFiles.write(file, frames)
        val staging = File(file.path + ".tmp")
        file.copyTo(staging)
        assertFails { LandmarkFiles.read(staging) }
        file.appendText("unexpected trailing bytes")
        assertFails { LandmarkFiles.read(file) }
    }

    @Test fun invalidTimestampSequenceIsNotPublished() {
        val file = File(directory.root, "${trainingId()}.mls")
        assertFails { LandmarkFiles.write(file, listOf(frames.first(), frames.first())) }
        assertFalse(file.exists())
        assertFails { LandmarkFiles.write(file, listOf(frames.first().copy(timestampMs = -1))) }
        assertFalse(file.exists())
    }
}
