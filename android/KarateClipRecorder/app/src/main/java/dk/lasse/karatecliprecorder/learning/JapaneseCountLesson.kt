package dk.lasse.karatecliprecorder.learning

import dk.lasse.karatecliprecorder.orders.TrainingOrder
import dk.lasse.karatecliprecorder.orders.TrainingOrderCatalog

data class JapaneseCountLessonItem(
    val number: String,
    val displayKanji: String,
    val standardJapanese: String,
    val spokenJapanese: String,
    val order: TrainingOrder,
) {
    /** The pronunciation shown and saved by the karate training flows. */
    val japanese: String
        get() = spokenJapanese
}

object JapaneseCountSequence {
    val expected: List<String> = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

    const val PRIMARY_LANGUAGE = "ja-JP"
}

object JapaneseCountLesson {
    val items: List<JapaneseCountLessonItem> = listOf(
        JapaneseCountLessonItem("1", "一", "ichi", "ich", requireNotNull(TrainingOrderCatalog.countOrder("1"))),
        JapaneseCountLessonItem("2", "二", "ni", "ni", requireNotNull(TrainingOrderCatalog.countOrder("2"))),
        JapaneseCountLessonItem("3", "三", "san", "san", requireNotNull(TrainingOrderCatalog.countOrder("3"))),
        JapaneseCountLessonItem("4", "四", "shi", "shi", requireNotNull(TrainingOrderCatalog.countOrder("4"))),
        JapaneseCountLessonItem("5", "五", "go", "go", requireNotNull(TrainingOrderCatalog.countOrder("5"))),
        JapaneseCountLessonItem("6", "六", "roku", "rok", requireNotNull(TrainingOrderCatalog.countOrder("6"))),
        JapaneseCountLessonItem("7", "七", "shichi", "shich", requireNotNull(TrainingOrderCatalog.countOrder("7"))),
        JapaneseCountLessonItem("8", "八", "hachi", "hach", requireNotNull(TrainingOrderCatalog.countOrder("8"))),
        JapaneseCountLessonItem("9", "九", "kyu", "kyu", requireNotNull(TrainingOrderCatalog.countOrder("9"))),
        JapaneseCountLessonItem("10", "十", "ju", "ju", requireNotNull(TrainingOrderCatalog.countOrder("10"))),
    )
}
