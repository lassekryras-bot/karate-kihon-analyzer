package dk.lasse.karatecliprecorder.orders

enum class TrainingOrder(
    val displayText: String,
    val soundResourceName: String? = null,
) {
    READY("Ready"),
    YOI("Yoi"),
    JODAN_ZUKI("Jodan zuki"),
    COUNT_1("Ichi", "order_ichi"),
    COUNT_2("Ni", "order_ni"),
    COUNT_3("San", "order_san"),
    COUNT_4("Shi", "order_shi"),
    COUNT_5("Go", "order_go"),
    COUNT_6("Roku", "order_roku"),
    COUNT_7("Shichi", "order_shichi"),
    COUNT_8("Hachi", "order_hachi"),
    COUNT_9("Ku", "order_ku"),
    COUNT_10("Ju", "order_ju"),
    SESSION_COMPLETE("Session complete"),
    SESSION_CANCELLED("Session cancelled"),
    SESSION_FAILED("Session failed"),
}
