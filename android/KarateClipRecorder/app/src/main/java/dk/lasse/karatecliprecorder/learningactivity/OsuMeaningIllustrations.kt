package dk.lasse.karatecliprecorder.learningactivity

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dk.lasse.karatecliprecorder.R

internal data class OsuIllustrationAsset(
    @DrawableRes val drawableRes: Int,
    @StringRes val contentDescriptionRes: Int,
    val isPlaceholder: Boolean = true,
)

/**
 * The only place where semantic Osu illustration roles know about Android resources.
 *
 * TODO: Replace these neutral icon placeholders with the approved assets listed in
 * docs/backlog/osu-meaning-illustrations.md. Activity state and layout must not depend on format.
 */
internal object OsuIllustrationAssets {
    fun resolve(illustration: OsuIllustration): OsuIllustrationAsset = when (illustration) {
        OsuIllustration.SENSEI_SPEAKING -> OsuIllustrationAsset(
            R.drawable.ic_tabler_karate,
            R.string.osu_illustration_sensei_speaking,
        )
        OsuIllustration.HEARD_YOU -> OsuIllustrationAsset(
            R.drawable.ic_tabler_volume,
            R.string.osu_illustration_heard_you,
        )
        OsuIllustration.PIZZA_DISTRACTION -> OsuIllustrationAsset(
            R.drawable.ic_tabler_help_circle,
            R.string.osu_illustration_pizza_distraction,
        )
        OsuIllustration.READY_GUARD -> OsuIllustrationAsset(
            R.drawable.ic_tabler_shield_check,
            R.string.osu_illustration_ready_guard,
        )
        OsuIllustration.SLEEP_DISTRACTION -> OsuIllustrationAsset(
            R.drawable.ic_tabler_clock,
            R.string.osu_illustration_sleep_distraction,
        )
        OsuIllustration.ONE_MORE -> OsuIllustrationAsset(
            R.drawable.ic_skill_coach_target,
            R.string.osu_illustration_one_more,
        )
        OsuIllustration.HOME_DISTRACTION -> OsuIllustrationAsset(
            R.drawable.ic_nav_home,
            R.string.osu_illustration_home_distraction,
        )
    }
}

/** Fixed-ratio visual slot whose resource can be replaced without changing lesson code. */
internal class OsuIllustrationView(
    context: Context,
    illustration: OsuIllustration,
) : FrameLayout(context) {
    init {
        val asset = OsuIllustrationAssets.resolve(illustration)
        minimumHeight = 112.dp()
        contentDescription = context.getString(asset.contentDescriptionRes)
        background = placeholderBackground(selected = false, correct = null)

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ImageView(context).apply {
                setImageResource(asset.drawableRes)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_accent))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(54.dp(), 54.dp()))
            if (asset.isPlaceholder) {
                addView(label(context.getString(R.string.osu_artwork_placeholder), 10f, Typeface.BOLD, Gravity.CENTER).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                    letterSpacing = 0.06f
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 8.dp()
                })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
            leftMargin = 8.dp()
            topMargin = 8.dp()
            rightMargin = 8.dp()
            bottomMargin = 8.dp()
        })
    }

    fun setSelectionState(selected: Boolean, correct: Boolean?) {
        background = placeholderBackground(selected, correct)
    }

    private fun placeholderBackground(selected: Boolean, correct: Boolean?): GradientDrawable =
        GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.progress_pale_fill))
            cornerRadius = 12.dp().toFloat()
            val strokeColor = when {
                selected && correct == true -> ContextCompat.getColor(context, R.color.app_success)
                selected -> ContextCompat.getColor(context, R.color.app_accent)
                else -> ContextCompat.getColor(context, R.color.app_border)
            }
            setStroke(
                if (selected) 2.dp() else 1.dp(),
                strokeColor,
                if (selected) 0f else 6.dp().toFloat(),
                if (selected) 0f else 4.dp().toFloat(),
            )
        }

    private fun label(text: String, size: Float, style: Int, gravity: Int) = TextView(context).apply {
        this.text = text
        textSize = size
        typeface = Typeface.create("sans-serif", style)
        this.gravity = gravity
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

internal class OsuMeaningQuestionView(
    context: Context,
    question: OsuMeaningQuestion,
    selectedIllustration: OsuIllustration?,
    answerIsCorrect: Boolean,
    onReplayOsu: () -> Unit,
    onSelect: (OsuIllustration) -> Unit,
) : LinearLayout(context) {
    private val accent = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val surface = ContextCompat.getColor(context, R.color.home_card_surface)

    init {
        orientation = VERTICAL
        setPadding(16.dp(), 16.dp(), 16.dp(), 18.dp())
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = 24.dp().toFloat()
            setStroke(1.dp(), border)
        }

        addView(senseiPrompt(question, onReplayOsu), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(label(context.getString(question.questionRes), 19f, Typeface.BOLD).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 18.dp()
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(answerCard(
                illustration = question.correctIllustration,
                labelRes = question.correctLabelRes,
                selectedIllustration = selectedIllustration,
                answerIsCorrect = answerIsCorrect,
                locked = answerIsCorrect,
                onSelect = onSelect,
            ), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(answerCard(
                illustration = question.distractionIllustration,
                labelRes = question.distractionLabelRes,
                selectedIllustration = selectedIllustration,
                answerIsCorrect = answerIsCorrect,
                locked = answerIsCorrect,
                onSelect = onSelect,
            ), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 10.dp() })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dp()
        })

        val feedback = when {
            selectedIllustration == null -> context.getString(R.string.osu_choose_picture_hint)
            answerIsCorrect -> context.getString(question.correctFeedbackRes)
            else -> context.getString(question.wrongFeedbackRes)
        }
        addView(label(feedback, 14f, if (selectedIllustration == null) Typeface.NORMAL else Typeface.BOLD).apply {
            setTextColor(if (answerIsCorrect) ContextCompat.getColor(context, R.color.app_success) else muted)
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dp()
        })
    }

    private fun senseiPrompt(question: OsuMeaningQuestion, onReplayOsu: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(OsuIllustrationView(context, OsuIllustration.SENSEI_SPEAKING), LayoutParams(108.dp(), 126.dp()))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label(context.getString(question.senseiSpeechRes), 16f, Typeface.BOLD).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.app_card_surface))
                    cornerRadius = 16.dp().toFloat()
                    setStroke(1.dp(), border)
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(Button(context).apply {
                text = context.getString(R.string.osu_hear_action)
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(accent)
                minimumHeight = 48.dp()
                contentDescription = context.getString(R.string.osu_hear_action)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_tabler_volume, 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(accent)
                compoundDrawablePadding = 7.dp()
                background = null
                setOnClickListener { onReplayOsu() }
            }, LayoutParams(LayoutParams.MATCH_PARENT, 48.dp()).apply { topMargin = 3.dp() })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp() })
    }

    private fun answerCard(
        illustration: OsuIllustration,
        @StringRes labelRes: Int,
        selectedIllustration: OsuIllustration?,
        answerIsCorrect: Boolean,
        locked: Boolean,
        onSelect: (OsuIllustration) -> Unit,
    ): LinearLayout {
        val selected = illustration == selectedIllustration
        val selectedCorrect = selected && answerIsCorrect
        val answerLabel = context.getString(labelRes)
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = 220.dp()
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            isClickable = !locked
            isFocusable = true
            isSelected = selected
            foreground = selectableItemBackground()
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.app_card_surface))
                cornerRadius = 16.dp().toFloat()
                val strokeColor = when {
                    selectedCorrect -> ContextCompat.getColor(context, R.color.app_success)
                    selected -> accent
                    else -> border
                }
                setStroke(if (selected) 3.dp() else 1.dp(), strokeColor)
            }
            setOnClickListener { if (!locked) onSelect(illustration) }
            ViewCompat.setStateDescription(this, when {
                selectedCorrect -> context.getString(R.string.osu_answer_state_correct)
                selected -> context.getString(R.string.osu_answer_state_try_another)
                locked -> context.getString(R.string.osu_answer_state_not_selected)
                else -> context.getString(R.string.osu_answer_state_available)
            })
            contentDescription = listOf(
                answerLabel,
                context.getString(OsuIllustrationAssets.resolve(illustration).contentDescriptionRes),
            ).joinToString(". ")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            })

            addView(OsuIllustrationView(context, illustration).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setSelectionState(selected, selectedCorrect.takeIf { selected })
            }, LayoutParams(LayoutParams.MATCH_PARENT, 126.dp()))
            addView(label(answerLabel, 15f, Typeface.BOLD, Gravity.CENTER), LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 10.dp() })
            if (selected) {
                addView(label(
                    context.getString(
                        if (selectedCorrect) R.string.osu_answer_badge_correct else R.string.osu_answer_badge_try_again,
                    ),
                    12f,
                    Typeface.BOLD,
                    Gravity.CENTER,
                ).apply { setTextColor(if (selectedCorrect) ContextCompat.getColor(context, R.color.app_success) else accent) },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp() })
            }
        }
    }

    private fun selectableItemBackground() = context.obtainStyledAttributes(
        intArrayOf(android.R.attr.selectableItemBackground),
    ).let { attributes ->
        val resourceId = attributes.getResourceId(0, 0)
        attributes.recycle()
        resourceId.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }
    }

    private fun label(
        text: String,
        size: Float,
        style: Int = Typeface.NORMAL,
        gravity: Int = Gravity.START,
    ) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", style)
        this.gravity = gravity
        setLineSpacing(2.dp().toFloat(), 1f)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
