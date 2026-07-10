package dk.lasse.karatecliprecorder.learning

data class FindYourWeaponState(
    val step: FindYourWeaponStep? = null,
    val isActive: Boolean = false,
    val isComplete: Boolean = false,
)
