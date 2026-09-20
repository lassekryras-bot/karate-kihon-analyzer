package dk.lasse.karatecliprecorder.profile

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R

sealed interface ProfileAvatarState {
    data class ActiveWithAvatar(val profile: Profile) : ProfileAvatarState
    data class ActiveWithoutAvatar(val profile: Profile) : ProfileAvatarState
    data object UnknownProfile : ProfileAvatarState
}

/**
 * Shared profile shortcut button supporting active avatar, neutral fallback, and unknown onboarding state.
 */
class ProfileAvatarButton @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var repository: ProfileRepository? = null
    private var clickAction: (() -> Unit)? = null

    private val avatarView = AvatarView(context).apply { showPortraitOutline = false }
    private val fallbackIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_profile_user)
        imageTintList = ContextCompat.getColorStateList(context, R.color.app_text_secondary)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        visibility = View.GONE
    }
    private val questionBadge = TextView(context).apply {
        text = "?"
        textSize = 10f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(context, R.color.app_card_surface))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        }
        visibility = View.GONE
    }

    private var profileState: ProfileAvatarState = ProfileAvatarState.UnknownProfile
    private var isUnknown = false

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp().toFloat()
        pathEffect = DashPathEffect(floatArrayOf(6.dp().toFloat(), 4.dp().toFloat()), 0f)
        color = ContextCompat.getColor(context, R.color.app_text_secondary)
    }

    private val listener: (Profile) -> Unit = { profile ->
        setProfileState(ProfileAvatarState.ActiveWithAvatar(profile))
    }
    private var observing = false

    constructor(
        context: Context,
        repository: ProfileRepository,
        onProfile: () -> Unit,
    ) : this(context) {
        this.repository = repository
        this.clickAction = onProfile
        setOnClickListener { clickAction?.invoke() }
        setProfileState(ProfileAvatarState.ActiveWithAvatar(repository.activeProfile()))
    }

    constructor(
        context: Context,
        state: ProfileAvatarState,
        onClick: () -> Unit,
    ) : this(context) {
        this.clickAction = onClick
        setOnClickListener { clickAction?.invoke() }
        setProfileState(state)
    }

    init {
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
            val drawable = it.getDrawable(0)
            it.recycle()
            drawable
        }
        clipToOutline = false
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)

        addView(avatarView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(fallbackIcon, LayoutParams(28.dp(), 28.dp(), Gravity.CENTER))
        addView(questionBadge, LayoutParams(14.dp(), 14.dp(), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = 1.dp()
            marginEnd = 1.dp()
        })
    }

    fun setProfileState(state: ProfileAvatarState) {
        profileState = state
        when (state) {
            is ProfileAvatarState.ActiveWithAvatar -> {
                isUnknown = false
                avatarView.visibility = View.VISIBLE
                avatarView.setProfile(state.profile)
                fallbackIcon.visibility = View.GONE
                questionBadge.visibility = View.GONE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, R.color.profile_avatar_background))
                    setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
                }
                contentDescription = "Open ${state.profile.name}'s profile"
            }
            is ProfileAvatarState.ActiveWithoutAvatar -> {
                isUnknown = false
                avatarView.visibility = View.GONE
                fallbackIcon.visibility = View.VISIBLE
                questionBadge.visibility = View.GONE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, R.color.profile_avatar_background))
                    setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
                }
                contentDescription = "Open ${state.profile.name}'s profile"
            }
            is ProfileAvatarState.UnknownProfile -> {
                isUnknown = true
                avatarView.visibility = View.GONE
                fallbackIcon.visibility = View.VISIBLE
                questionBadge.visibility = View.VISIBLE
                background = null
                contentDescription = context.getString(R.string.select_profile)
            }
        }
        invalidate()
    }

    fun pulseAttention() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val scaleX = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.15f, 1f, 1.15f, 1f)
        val scaleY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 1.15f, 1f, 1.15f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 750
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isUnknown) {
            val radius = (minOf(width, height) / 2f) - 2.dp()
            canvas.drawCircle(width / 2f, height / 2f, radius, dashedPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val repo = repository
        if (repo != null && !observing) {
            observing = true
            repo.addActiveProfileListener(listener)
        }
    }

    override fun onDetachedFromWindow() {
        val repo = repository
        if (repo != null && observing) {
            observing = false
            repo.removeActiveProfileListener(listener)
        }
        super.onDetachedFromWindow()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
