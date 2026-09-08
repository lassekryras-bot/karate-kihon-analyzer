package dk.lasse.karatecliprecorder.profile

object AvatarCarouselModel {
    fun settledDelta(offset: Float, step: Float): Int = when {
        step <= 0f -> 0
        offset <= -step * 0.3f -> 1
        offset >= step * 0.3f -> -1
        else -> 0
    }
    const val VISIBLE_COUNT = 5

    fun visibleBaseIds(selectedIndex: Int, baseIds: List<String> = Profile.AVATAR_BASE_IDS): List<String> {
        require(baseIds.isNotEmpty())
        val center = selectedIndex.floorMod(baseIds.size)
        return (-2..2).map { offset -> baseIds[(center + offset).floorMod(baseIds.size)] }
    }

    fun move(selectedIndex: Int, delta: Int, size: Int = Profile.AVATAR_BASE_IDS.size): Int {
        require(size > 0)
        return (selectedIndex + delta).floorMod(size)
    }

    private fun Int.floorMod(modulus: Int) = ((this % modulus) + modulus) % modulus
}
