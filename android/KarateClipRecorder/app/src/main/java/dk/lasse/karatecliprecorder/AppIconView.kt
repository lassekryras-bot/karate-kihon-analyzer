package dk.lasse.karatecliprecorder

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat

/**
 * Shared entry point for general-purpose application icons.
 *
 * General-purpose resources are Tabler Icons 3.46.0 outlines at their native 24 x 24 grid and
 * 2 px stroke. The fitted belt silhouette is retained only for belt-rank profile controls.
 */
enum class AppIcon(@DrawableRes val drawableRes: Int) {
    CAMERA(R.drawable.ic_tabler_camera),
    SHIELD_CHECK(R.drawable.ic_tabler_shield_check),
    VOLUME(R.drawable.ic_tabler_volume),
    MICROPHONE(R.drawable.ic_tabler_microphone),
    CLOCK(R.drawable.ic_tabler_clock),
    PALETTE(R.drawable.ic_tabler_palette),
    DATABASE(R.drawable.ic_tabler_database),
    TRASH(R.drawable.ic_tabler_trash),
    CODE(R.drawable.ic_tabler_code),
    BUG(R.drawable.ic_tabler_bug),
    INFO_CIRCLE(R.drawable.ic_tabler_info_circle),
    HELP_CIRCLE(R.drawable.ic_tabler_help_circle),
    SHIELD(R.drawable.ic_tabler_shield),
    CHEVRON_RIGHT(R.drawable.ic_tabler_chevron_right),
    ARROW_LEFT(R.drawable.ic_tabler_arrow_left),
    HOME(R.drawable.ic_nav_home),
    CHART_BAR(R.drawable.ic_nav_progress),
    SETTINGS(R.drawable.ic_nav_settings),
    KARATE(R.drawable.ic_tabler_karate),
    KARATE_BELT(R.drawable.ic_profile_belt),
}

class AppIconView(
    context: Context,
    icon: AppIcon,
    sizeDp: Int = DEFAULT_SIZE_DP,
    tint: ColorStateList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_accent)),
) : AppCompatImageView(context) {
    init {
        setImageResource(icon.drawableRes)
        imageTintList = tint
        scaleType = ScaleType.CENTER_INSIDE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = ViewGroup.LayoutParams(sizeDp.dp(), sizeDp.dp())
    }

    fun setIconColor(color: Int) {
        imageTintList = ColorStateList.valueOf(color)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_SIZE_DP = 24
    }
}
