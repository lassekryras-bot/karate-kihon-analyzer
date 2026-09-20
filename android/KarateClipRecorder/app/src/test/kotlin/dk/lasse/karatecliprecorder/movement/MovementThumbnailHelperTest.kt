package dk.lasse.karatecliprecorder.movement

import android.graphics.Bitmap
import android.os.Looper
import dk.lasse.karatecliprecorder.training.SessionMovement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MovementThumbnailHelperTest {

    private lateinit var tempFile: File

    @Before
    fun setup() {
        MovementThumbnailHelper.clearCache()
        tempFile = File.createTempFile("test_video", ".mp4")
        tempFile.writeBytes(ByteArray(100))
    }

    @After
    fun tearDown() {
        MovementThumbnailHelper.clearCache()
        if (tempFile.exists()) tempFile.delete()
    }

    @Test
    fun getCachedThumbnailReturnsNullWhenNotCached() {
        val movement = SessionMovement(
            sessionId = "s1",
            startUs = 1_000_000,
            endUs = 2_000_000,
            playbackStartUs = 800_000,
            playbackEndUs = 2_200_000,
            analysisFrameUs = 1_500_000,
            segmentationSource = "test",
            segmentationVersion = "1",
        )
        assertNull(MovementThumbnailHelper.getCachedThumbnail(movement))
    }

    @Test
    fun loadThumbnailAsyncReturnsNullForMissingFileWithoutInvokingExecutor() {
        val movement = SessionMovement(
            sessionId = "s1",
            startUs = 1_000_000,
            endUs = 2_000_000,
            playbackStartUs = 800_000,
            playbackEndUs = 2_200_000,
            analysisFrameUs = 1_500_000,
            segmentationSource = "test",
            segmentationVersion = "1",
        )
        var callbackInvoked = false
        var executorInvoked = false
        val executor = java.util.concurrent.Executor {
            executorInvoked = true
            it.run()
        }

        MovementThumbnailHelper.loadThumbnailAsync(
            executor = executor,
            videoFile = File("non_existent_file.mp4"),
            movement = movement,
        ) { bitmap ->
            callbackInvoked = true
            assertNull(bitmap)
        }

        assertTrue(callbackInvoked)
        assertFalse(executorInvoked)
    }

    @Test
    fun loadThumbnailAsyncExtractsAndCachesFrame() {
        val targetUs = 1_500_000L
        val movement = SessionMovement(
            sessionId = "s1",
            startUs = 1_000_000,
            endUs = 2_000_000,
            playbackStartUs = 800_000,
            playbackEndUs = 2_200_000,
            analysisFrameUs = targetUs,
            segmentationSource = "test",
            segmentationVersion = "1",
        )

        val sampleBitmap = Bitmap.createBitmap(144, 192, Bitmap.Config.ARGB_8888)
        ShadowMediaMetadataRetriever.addFrame(tempFile.absolutePath, targetUs, sampleBitmap)

        val latch = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var loadedBitmap: Bitmap? = null

        MovementThumbnailHelper.loadThumbnailAsync(
            executor = executor,
            videoFile = tempFile,
            movement = movement,
            targetWidth = 144,
            targetHeight = 192,
        ) { bmp ->
            loadedBitmap = bmp
            latch.countDown()
        }

        // Wait for background execution and drain main looper
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(loadedBitmap)
        // Now it should be cached
        val cached = MovementThumbnailHelper.getCachedThumbnail(movement)
        assertNotNull(cached)

        // Clear cache
        MovementThumbnailHelper.clearCache()
        assertNull(MovementThumbnailHelper.getCachedThumbnail(movement))
        executor.shutdown()
    }
}
