package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.profile.ProfileAvatarButton
import dk.lasse.karatecliprecorder.profile.ProfileRepository

/** Passive mode chooser. Its three cards share the available height without scrolling. */
class TrainScreenView(
    context: Context,
    repository: ProfileRepository,
    onProfile: () -> Unit,
    onLearn: () -> Unit,
    onPractice: () -> Unit,
    onSkillCoach: () -> Unit,
    onHome: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        addView(MainPageHeader(context, "Train", "How would you like to train?",
            ProfileAvatarButton(context, repository, onProfile)), LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 16.dp())
            addView(modeCard(R.drawable.ic_learn_torii, "Learn", "Build your understanding and skills through step-by-step lessons.", onLearn), LayoutParams(-1, 0, 1f))
            addView(modeCard(R.drawable.ic_practice, "Practice", "Choose punches, kicks, or other exercises and build a practice session around them.", onPractice), LayoutParams(-1, 0, 1f).apply { topMargin = 12.dp() })
            addView(modeCard(R.drawable.ic_skill_coach_target, "Skill Coach", "Explore your technique with measured feedback. Slow down and work on one detail at a time.", onSkillCoach), LayoutParams(-1, 0, 1f).apply { topMargin = 12.dp() })
        }, LayoutParams(-1, 0, 1f))
        addView(AppBottomNavigationView(context, AppDestination.TRAIN, onHome, {}, onProgress, onSettings),
            LayoutParams(-1, AppBottomNavigationView.BASE_HEIGHT_DP.dp()))
    }

    private fun modeCard(icon: Int, title: String, description: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20.dp(), 12.dp(), 16.dp(), 12.dp())
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.app_card_surface))
            cornerRadius = 14.dp().toFloat()
            setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
        }
        isClickable = true
        isFocusable = true
        contentDescription = "$title. $description"
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.content_icon_tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(56.dp(), 56.dp()))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(TextView(context).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
            })
            addView(TextView(context).apply {
                text = description
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            }, LayoutParams(-1, -2).apply { topMargin = 6.dp() })
        }, LayoutParams(0, -2, 1f).apply { marginStart = 18.dp(); marginEnd = 8.dp() })
        addView(AppIconView(context, AppIcon.CHEVRON_RIGHT).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(20.dp(), 24.dp()))
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
