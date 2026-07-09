package dk.lasse.karatecliprecorder.orders

object TrainingOrderCatalog {
    val all: List<TrainingOrder> = TrainingOrder.entries

    fun countOrder(strikeIndex: Int): TrainingOrder? = when (strikeIndex) {
        1 -> TrainingOrder.COUNT_1
        2 -> TrainingOrder.COUNT_2
        3 -> TrainingOrder.COUNT_3
        4 -> TrainingOrder.COUNT_4
        5 -> TrainingOrder.COUNT_5
        6 -> TrainingOrder.COUNT_6
        7 -> TrainingOrder.COUNT_7
        8 -> TrainingOrder.COUNT_8
        9 -> TrainingOrder.COUNT_9
        10 -> TrainingOrder.COUNT_10
        else -> null
    }
}
