package dk.lasse.karatecliprecorder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** A rounded, divided card that can host any number of [SettingsRowView] instances. */
class SettingsCardView(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        elevation = 1.dp().toFloat()
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.app_card_surface))
            cornerRadius = 16.dp().toFloat()
            setStroke(1.dp(), ContextCompat.getColor(context, R.color.app_border))
        }
    }

    fun addSettingsRow(row: SettingsRowView) {
        if (childCount > 0) {
            addView(View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.app_divider))
            }, LayoutParams(LayoutParams.MATCH_PARENT, 1.dp()).apply {
                marginStart = 56.dp()
                marginEnd = 16.dp()
            })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

/** Section heading and its single settings card. */
class SettingsSectionView(context: Context, heading: String) : LinearLayout(context) {
    private val card = SettingsCardView(context)

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = heading
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            ViewCompat.setAccessibilityHeading(this, true)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 22.dp()
            bottomMargin = 8.dp()
            marginStart = 2.dp()
        })
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun addRow(row: SettingsRowView): SettingsSectionView = apply {
        card.addSettingsRow(row)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

/**
 * One flexible settings row. The same structure supports navigation, values, status, and toggles.
 */
class SettingsRowView(
    context: Context,
    icon: AppIcon,
    private val title: String,
    private val description: String,
) : LinearLayout(context) {
    private val endContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 82.dp()
        setPadding(16.dp(), 12.dp(), 12.dp(), 12.dp())

        addView(AppIconView(context, icon), LayoutParams(24.dp(), 24.dp()).apply {
            marginEnd = 16.dp()
        })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = description
                textSize = 13f
                setLineSpacing(1.dp().toFloat(), 1f)
                setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2.dp()
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(endContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = 12.dp()
        })
    }

    fun configureAsNavigation(value: String? = null, onClick: () -> Unit): TextView? {
        endContainer.removeAllViews()
        val valueView = value?.let(::addValue)
        endContainer.addView(AppIconView(
            context = context,
            icon = AppIcon.CHEVRON_RIGHT,
            sizeDp = 20,
            tint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.app_text_secondary)),
        ), LayoutParams(20.dp(), 20.dp()).apply { marginStart = 4.dp() })
        setAction(onClick, value)
        return valueView
    }

    fun configureAsToggle(initialValue: Boolean, onChanged: (Boolean) -> Unit): SwitchCompat {
        endContainer.removeAllViews()
        val toggle = SwitchCompat(context).apply {
            minimumWidth = 48.dp()
            minimumHeight = 48.dp()
            isChecked = initialValue
            splitTrack = false
            showText = false
            thumbTintList = ContextCompat.getColorStateList(context, R.color.settings_switch_thumb_tint)
            trackTintList = ContextCompat.getColorStateList(context, R.color.settings_switch_track_tint)
            contentDescription = "$title. $description"
            updateSwitchStateDescription(initialValue)
            setOnCheckedChangeListener { _, checked ->
                updateSwitchStateDescription(checked)
                onChanged(checked)
            }
        }
        endContainer.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp()))
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        foreground = selectableItemBackground()
        setOnClickListener { toggle.performClick() }
        return toggle
    }

    fun setStatus(text: String, color: Int, icon: AppIcon? = null) {
        endContainer.removeAllViews()
        endContainer.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.END
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        icon?.let {
            endContainer.addView(AppIconView(
                context = context,
                icon = it,
                sizeDp = 18,
                tint = ColorStateList.valueOf(color),
            ), LayoutParams(18.dp(), 18.dp()).apply { marginStart = 5.dp() })
        }
    }

    fun setAction(onClick: () -> Unit, value: String? = null) {
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        foreground = selectableItemBackground()
        contentDescription = listOfNotNull(title, description, value).joinToString(". ")
        setOnClickListener { onClick() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }

    fun clearAction() {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        foreground = null
        contentDescription = null
        setOnClickListener(null)
        ViewCompat.setAccessibilityDelegate(this, null)
    }

    private fun addValue(value: String): TextView = TextView(context).apply {
        text = value
        textSize = 14f
        maxWidth = 132.dp()
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
        setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
        endContainer.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun SwitchCompat.updateSwitchStateDescription(checked: Boolean) {
        ViewCompat.setStateDescription(this, if (checked) "On" else "Off")
    }

    private fun selectableItemBackground() = context.obtainStyledAttributes(
        intArrayOf(android.R.attr.selectableItemBackground),
    ).let { attributes ->
        val resourceId = attributes.getResourceId(0, 0)
        attributes.recycle()
        resourceId.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
