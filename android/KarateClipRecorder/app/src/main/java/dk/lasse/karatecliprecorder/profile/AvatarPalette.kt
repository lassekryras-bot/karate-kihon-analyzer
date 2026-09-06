package dk.lasse.karatecliprecorder.profile

import android.graphics.Color
import androidx.core.graphics.ColorUtils

object AvatarPalette {
    private val skinStops = intArrayOf(
        Color.rgb(255, 224, 198),
        Color.rgb(246, 190, 137),
        Color.rgb(211, 139, 86),
        Color.rgb(153, 82, 45),
        Color.rgb(92, 47, 29),
    )
    private val hairStops = intArrayOf(
        Color.rgb(20, 18, 17),
        Color.rgb(55, 37, 28),
        Color.rgb(99, 58, 35),
        Color.rgb(154, 68, 35),
        Color.rgb(190, 132, 68),
        Color.rgb(232, 190, 105),
    )

    fun skin(position: Float): Int = interpolateStops(skinStops, position)
    fun hair(position: Float): Int = interpolateStops(hairStops, position)

    fun belt(rank: BeltRank): Int = when (rank) {
        BeltRank.WHITE -> Color.rgb(239, 239, 235)
        BeltRank.ORANGE -> Color.rgb(231, 112, 15)
        BeltRank.BLUE -> Color.rgb(22, 98, 171)
        BeltRank.YELLOW -> Color.rgb(241, 190, 19)
        BeltRank.GREEN -> Color.rgb(24, 119, 56)
        BeltRank.BROWN -> Color.rgb(104, 56, 29)
        BeltRank.BLACK -> Color.rgb(25, 25, 25)
    }

    fun shade(color: Int, tone: Float): Int = when {
        tone < 1f -> ColorUtils.blendARGB(Color.BLACK, color, tone.coerceIn(0f, 1f))
        tone > 1f -> ColorUtils.blendARGB(color, Color.WHITE, ((tone - 1f) * 0.55f).coerceIn(0f, 1f))
        else -> color
    }

    private fun interpolateStops(stops: IntArray, position: Float): Int {
        val scaled = position.coerceIn(0f, 1f) * (stops.size - 1)
        val low = scaled.toInt().coerceAtMost(stops.lastIndex)
        val high = (low + 1).coerceAtMost(stops.lastIndex)
        return ColorUtils.blendARGB(stops[low], stops[high], scaled - low)
    }
}
