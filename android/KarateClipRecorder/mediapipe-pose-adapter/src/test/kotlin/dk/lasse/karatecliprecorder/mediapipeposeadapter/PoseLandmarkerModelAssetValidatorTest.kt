package dk.lasse.karatecliprecorder.mediapipeposeadapter

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PoseLandmarkerModelAssetValidatorTest {
    @Test fun missingAssetProducesReadableFailure() {
        assertFailsWith<MissingPoseLandmarkerModelException> {
            PoseLandmarkerModelAssetValidator(assetExists = { false }).validate()
        }
    }

    @Test fun existingAssetPasses() {
        PoseLandmarkerModelAssetValidator(assetExists = { it == POSE_LANDMARKER_MODEL_ASSET_PATH }).validate()
    }
}
