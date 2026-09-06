package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R

/** Shared active-profile shortcut used exclusively by top-level destinations. */
class ProfileAvatarButton(
    context: Context,
    private val repository: ProfileRepository,
    onProfile: () -> Unit,
) : FrameLayout(context) {
    private val avatar = AvatarView(context)
    private val listener: (Profile) -> Unit = { profile ->
        avatar.setProfile(profile)
        contentDescription = "Open ${profile.name}'s profile"
    }
    private var observing = false

    init {
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
            val drawable = it.getDrawable(0)
            it.recycle()
            drawable
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.profile_avatar_background))
            setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
        }
        clipToOutline = true
        isClickable = true
        isFocusable = true
        setOnClickListener { onProfile() }
        addView(avatar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observing) {
            observing = true
            repository.addActiveProfileListener(listener)
        }
    }

    override fun onDetachedFromWindow() {
        if (observing) {
            observing = false
            repository.removeActiveProfileListener(listener)
        }
        super.onDetachedFromWindow()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
