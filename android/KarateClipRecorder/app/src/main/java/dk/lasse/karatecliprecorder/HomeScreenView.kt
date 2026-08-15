package dk.lasse.karatecliprecorder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The product landing screen. It is deliberately a passive view: constructing it never touches
 * CameraX, MediaPipe, or runtime permissions. Training infrastructure is entered only by a user
 * action supplied through the callbacks below.
 */
class HomeScreenView(
    context: Context,
    onContinue: () -> Unit,
    onLearn: () -> Unit,
    onPractice: () -> Unit,
    onSkillCoach: () -> Unit,
    onTrain: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val red = Color.rgb(190, 0, 12)
    private val ink = Color.rgb(24, 24, 24)
    private val muted = Color.rgb(92, 92, 92)
    private val paper = Color.rgb(252, 250, 247)

    init {
        setBackgroundColor(Color.WHITE)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 110.dp())
            addView(header())
            addView(sectionLabel("CONTINUE WHERE YOU LEFT OFF"))
            addView(continueCard(onContinue))
            addView(sectionLabel("QUICK ACTIONS"))
            addView(quickActions(onLearn, onPractice, onSkillCoach))
            addView(sectionLabel("TODAY'S FOCUS"))
            addView(focusCard())
            addView(sectionLabel("RECENT ACTIVITY"))
            addView(activityCard())
            addView(sectionLabel("LEARNING PROGRESS"))
            addView(progressCard())
        }
        addView(ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val navigation = bottomNavigation(onTrain, onProgress, onSettings)
        addView(navigation, LayoutParams(LayoutParams.MATCH_PARENT, 82.dp(), Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            content.setPadding(20.dp(), systemBars.top + 12.dp(), 20.dp(), navigationBars.bottom + 110.dp())
            navigation.layoutParams = (navigation.layoutParams as LayoutParams).apply {
                height = 82.dp()
                bottomMargin = navigationBars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun header() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("Karate Kihon Analyzer", 25f, Typeface.BOLD), LinearLayout.LayoutParams(0, 70.dp(), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(label("●", 23f, Typeface.NORMAL, Gravity.CENTER).apply {
            contentDescription = "Profile"
            background = outlinedCircle()
        }, LinearLayout.LayoutParams(46.dp(), 46.dp()))
    }

    private fun continueCard(onClick: () -> Unit) = card().apply {
        orientation = LinearLayout.VERTICAL
        addView(label("◎   Skill Coach — Jōdan Punch", 19f, Typeface.BOLD).apply { setTextColor(ink) })
        addView(label("Last used 2 days ago", 14f).apply {
            setTextColor(muted)
            setPadding(34.dp(), 4.dp(), 0, 14.dp())
        })
        addView(actionButton("Continue", onClick))
    }

    private fun quickActions(onLearn: () -> Unit, onPractice: () -> Unit, onCoach: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val actions = listOf(
                Triple("▰", "Learn\nStep-by-step lessons", onLearn),
                Triple("✦", "Practice\nFree practice & drills", onPractice),
                Triple("◎", "Skill Coach\nTechnique feedback", onCoach),
            )
            actions.forEachIndexed { index, (icon, copy, callback) ->
                addView(card().apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { callback() }
                    isClickable = true
                    isFocusable = true
                    contentDescription = copy.replace('\n', ' ')
                    addView(label("$icon\n$copy", 14f, Typeface.BOLD, Gravity.CENTER).apply {
                        setTextColor(if (index == 0) ink else red)
                    })
                }, LinearLayout.LayoutParams(0, 142.dp(), 1f).apply {
                    if (index > 0) marginStart = 8.dp()
                })
            }
        }

    private fun focusCard() = card().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("🥋", 34f, gravity = Gravity.CENTER), LinearLayout.LayoutParams(58.dp(), 58.dp()))
        addView(label("Work on\nChūdan Punch\nTarget awareness", 16f, Typeface.BOLD), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(label("›", 30f, gravity = Gravity.CENTER), LinearLayout.LayoutParams(30.dp(), 50.dp()))
    }

    private fun activityCard() = card().apply {
        orientation = LinearLayout.VERTICAL
        activityRow("◎", "Skill Coach — Jōdan Punch", "10 punches analyzed  ·  2 days ago")
        divider()
        activityRow("✦", "Practice — Speed & Control", "8 min session  ·  3 days ago")
        divider()
        activityRow("▰", "Learn — Chūdan Punch", "Step 2: Target awareness  ·  4 days ago")
    }

    private fun LinearLayout.activityRow(icon: String, title: String, detail: String) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(icon, 23f, Typeface.BOLD, Gravity.CENTER).apply { setTextColor(red) }, LinearLayout.LayoutParams(42.dp(), 54.dp()))
            addView(label("$title\n$detail", 14f, Typeface.BOLD).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(label("›", 25f, gravity = Gravity.CENTER), LinearLayout.LayoutParams(25.dp(), 54.dp()))
        })
    }

    private fun LinearLayout.divider() = addView(View(context).apply { setBackgroundColor(Color.rgb(232, 228, 223)) }, LayoutParams(LayoutParams.MATCH_PARENT, 1.dp()))

    private fun progressCard() = card().apply {
        orientation = LinearLayout.VERTICAL
        progressRow("Jōdan Punch", 4, 7)
        progressRow("Chūdan Punch", 2, 6)
        addView(label("Gedan Punch                         Not started", 14f, Typeface.BOLD))
    }

    private fun LinearLayout.progressRow(title: String, value: Int, max: Int) {
        addView(label("$title                                      $value / $max steps", 14f, Typeface.BOLD))
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = max
            progress = value
            progressTintList = android.content.res.ColorStateList.valueOf(red)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 10.dp()).apply { bottomMargin = 12.dp() })
    }

    private fun bottomNavigation(
        onTrain: () -> Unit,
        onProgress: () -> Unit,
        onSettings: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        elevation = 12.dp().toFloat()
        setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        setBackgroundColor(Color.WHITE)
        listOf(
            navigationItem("Home", R.drawable.ic_nav_home, selected = true, onClick = {}),
            navigationItem("Train", R.drawable.ic_nav_train_belt, onClick = onTrain),
            navigationItem("Progress", R.drawable.ic_nav_progress, onClick = onProgress),
            navigationItem("Settings", R.drawable.ic_nav_settings, onClick = onSettings),
        ).forEach { item ->
            addView(item, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun navigationItem(
        label: String,
        iconRes: Int,
        selected: Boolean = false,
        onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = 48.dp()
        minimumHeight = 48.dp()
        isSelected = selected
        isClickable = true
        isFocusable = true
        contentDescription = label
        ViewCompat.setStateDescription(this, if (selected) "Selected" else "Not selected")
        setOnClickListener { onClick() }

        val tint = ContextCompat.getColorStateList(context, R.color.nav_icon_tint)
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = tint
            isSelected = selected
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(24.dp(), 24.dp()))
        addView(label(label, 13f, Typeface.BOLD, Gravity.CENTER).apply {
            setTextColor(tint)
            isSelected = selected
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp()
        })
    }

    private fun sectionLabel(text: String) = label(text, 13f, Typeface.BOLD).apply {
        setTextColor(muted)
        setPadding(2.dp(), 18.dp(), 0, 8.dp())
    }

    private fun actionButton(text: String, onClick: () -> Unit) = label(text, 17f, Typeface.BOLD, Gravity.CENTER).apply {
        setTextColor(Color.WHITE)
        background = rounded(red, 10.dp().toFloat())
        setOnClickListener { onClick() }
        isClickable = true
        isFocusable = true
        contentDescription = text
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 48.dp())
    }

    private fun card() = LinearLayout(context).apply {
        setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        background = rounded(paper, 14.dp().toFloat(), Color.rgb(230, 224, 216))
        elevation = 2.dp().toFloat()
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", style)
        this.gravity = gravity
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let { setStroke(1.dp(), it) }
    }

    private fun outlinedCircle() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(2.dp(), ink)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
