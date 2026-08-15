package dk.lasse.karatecliprecorder.learning

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PunchHeightLatestPublisherTest {
    @Test fun completedStagingDirectoryReplacesTheOldLatestDirectory() {
        val root = Files.createTempDirectory("punch-height-publish").toFile()
        try {
            val latest = root.resolve("latest").apply { mkdirs(); resolve("old.txt").writeText("old") }
            val staging = root.resolve(".staging-test").apply { mkdirs(); resolve("session.json").writeText("new") }

            PunchHeightLatestPublisher().publish(staging, latest)

            assertTrue(latest.resolve("session.json").isFile)
            assertFalse(latest.resolve("old.txt").exists())
            assertFalse(staging.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedPublicationRestoresThePreviousLatestDirectory() {
        val root = Files.createTempDirectory("punch-height-rollback").toFile()
        try {
            val latest = root.resolve("latest").apply { mkdirs(); resolve("old.txt").writeText("old") }
            val missingStaging = root.resolve("missing")

            assertFails { PunchHeightLatestPublisher().publish(missingStaging, latest) }

            assertEquals("old", latest.resolve("old.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun interruptedPublicationCanRecoverItsBackup() {
        val root = Files.createTempDirectory("punch-height-recover").toFile()
        try {
            val latest = root.resolve("latest")
            root.resolve(".latest-backup").apply { mkdirs(); resolve("session.json").writeText("previous") }

            PunchHeightLatestPublisher().recover(latest)

            assertEquals("previous", latest.resolve("session.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
