package dk.lasse.karatecliprecorder

import java.util.Locale

data class GuidedStrikePlan(
    val index: Int,
    val japaneseCount: String,
    val expectedSide: StrikeSide,
    val fileName: String = String.format(
        Locale.US,
        "strike_%03d_%s.mp4",
        index,
        expectedSide.metadataValue,
    ),
)
