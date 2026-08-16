package dk.lasse.karatecliprecorder.learningactivity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson
import dk.lasse.karatecliprecorder.learning.JapaneseCountLessonItem
import dk.lasse.karatecliprecorder.learning.JapaneseCountLevel1State

/** Japanese-specific runner hosted by the reusable [ActivityShellView]. */
@SuppressLint("ViewConstructor")
class JapaneseCountingPracticeView(
    context: Context,
    onExit: () -> Unit,
    private val onStartPractice: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onReplay: () -> Unit,
    private val onPracticeAgain: () -> Unit,
    private val onContinueToTest: () -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.app_accent)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val surface = ContextCompat.getColor(context, R.color.app_card_surface)
    private val warmSurface = ContextCompat.getColor(context, R.color.home_card_surface)
    private val paleRed = ContextCompat.getColor(context, R.color.progress_pale_fill)
    private val shell = ActivityShellView(context, onExit)

    var presentation: JapaneseCountingPracticePresentation = JapaneseCountingPracticePresentation.ready()
        private set

    init {
        addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shell.setHeader("Japanese Counting", presentation.pathPosition)
        shell.setContext("LEARN", "JAPANESE")
        render(presentation)
    }

    fun renderReady() = render(JapaneseCountingPracticePresentation.ready())

    fun renderLevel1State(state: JapaneseCountLevel1State) {
        render(JapaneseCountingPracticePresentation.fromLevel1(state))
    }

    private fun render(next: JapaneseCountingPracticePresentation) {
        presentation = next
        shell.setHeader("Japanese Counting", next.pathPosition)
        when (next.shellState) {
            ActivityShellState.READY -> renderReadyState()
            ActivityShellState.ACTIVE -> renderActiveState(requireNotNull(next.item), requireNotNull(next.itemIndex))
            ActivityShellState.COMPLETE -> renderCompleteState()
            ActivityShellState.RESULT,
            ActivityShellState.ERROR -> error("Japanese Counting Practice does not use ${next.shellState} yet.")
        }
    }

    private fun renderReadyState() {
        shell.setHeading(
            "Practice Numbers 1–10",
            "Learn the Japanese numbers used in the dojo.\nThis is the first step before the test.",
        )
        shell.setRunnerContent(introCard())
        shell.setProgressContent(null)
        shell.setActions(
            secondary = null,
            primary = ActivityShellAction("Start practice  →", onClick = onStartPractice),
        )
    }

    private fun renderActiveState(item: JapaneseCountLessonItem, itemIndex: Int) {
        shell.setHeading("Practice Numbers 1–10", "Learn one number at a time.")
        shell.setRunnerContent(numberCard(item))
        shell.setProgressContent(numberProgress(itemIndex))
        shell.setActions(
            secondary = ActivityShellAction(
                label = "Previous",
                enabled = presentation.previousEnabled,
                onClick = onPrevious,
            ),
            primary = ActivityShellAction(presentation.nextLabel, onClick = onNext),
        )
    }

    private fun renderCompleteState() {
        shell.setHeading("Great job!", "You practiced all 10 Japanese numbers.")
        shell.setRunnerContent(completionContent())
        shell.setProgressContent(null)
        shell.setActions(
            secondary = ActivityShellAction("Practice again", onClick = onPracticeAgain),
            primary = ActivityShellAction("Continue to test  →", onClick = onContinueToTest),
        )
    }

    private fun introCard() = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(), 18.dp(), 16.dp(), 16.dp())
        addView(label("Why this matters in karate", 18f, Typeface.BOLD).apply { setTextColor(red) })
        addView(label(
            "In the dojo, the instructor calls each count to cue the next punch or technique. " +
                "The class moves together with the rhythm of the counting.",
            15f,
        ).apply { setPadding(0, 8.dp(), 0, 13.dp()) })
        addView(divider())
        addView(label(
            "You will often hear the numbers pronounced shorter and sharper than in normal Japanese speech. For example:",
            15f,
        ).apply { setPadding(0, 13.dp(), 0, 10.dp()) })
        addView(pronunciationExamples())
        addView(label("This lesson uses that short dojo-style pronunciation.", 15f).apply {
            setPadding(0, 12.dp(), 0, 13.dp())
        })
        addView(divider())
        addView(label("What you’ll do", 18f, Typeface.BOLD).apply {
            setTextColor(red)
            setPadding(0, 13.dp(), 0, 6.dp())
        })
        listOf(
            "See the number, Japanese character, and name.",
            "Hear the dojo pronunciation automatically.",
            "Repeat it out loud.",
            "Use Replay as often as you like.",
            "Press Next when you’re ready.",
        ).forEach { copy ->
            addView(label("•  $copy", 14f).apply { setPadding(0, 4.dp(), 0, 4.dp()) })
        }
        addView(divider(), matchWrap().apply { topMargin = 10.dp() })
        addView(label("10 numbers  ·  about 2–3 minutes", 14f, Typeface.BOLD).apply {
            setTextColor(muted)
            setPadding(0, 12.dp(), 0, 0)
        })
    }

    private fun pronunciationExamples() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val examples = JapaneseCountLesson.items.filter { it.number in setOf("1", "6", "7", "8") }
        examples.forEachIndexed { index, item ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(3.dp(), 8.dp(), 3.dp(), 8.dp())
                background = roundedSurface(surface, 12.dp(), border)
                addView(label(item.displayKanji, 21f, Typeface.BOLD, Gravity.CENTER))
                addView(label(item.standardJapanese, 12f, Typeface.BOLD, Gravity.CENTER))
                addView(label("“${item.spokenJapanese}”", 12f, Typeface.BOLD, Gravity.CENTER).apply {
                    setTextColor(red)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = 7.dp()
            })
        }
    }

    private fun numberCard(item: JapaneseCountLessonItem) = card().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        minimumHeight = 360.dp()
        setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
        contentDescription = "Number ${item.number}, ${item.displayKanji}, ${item.standardJapanese}"
        addView(label(item.number, 76f, Typeface.BOLD, Gravity.CENTER).apply { setTextColor(red) })
        addView(divider(), LinearLayout.LayoutParams(108.dp(), 1.dp()).apply {
            topMargin = 2.dp()
            bottomMargin = 10.dp()
        })
        addView(label(item.displayKanji, 50f, Typeface.NORMAL, Gravity.CENTER))
        addView(divider(), LinearLayout.LayoutParams(108.dp(), 1.dp()).apply {
            topMargin = 8.dp()
            bottomMargin = 12.dp()
        })
        addView(label(item.standardJapanese, 29f, Typeface.BOLD, Gravity.CENTER))
        addView(replayButton(), LinearLayout.LayoutParams(76.dp(), 76.dp()).apply { topMargin = 16.dp() })
        addView(label("Replay", 15f, Typeface.BOLD, Gravity.CENTER).apply { setPadding(0, 7.dp(), 0, 0) })
    }

    private fun replayButton() = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(red)
        }
        isClickable = true
        isFocusable = true
        contentDescription = "Replay ${presentation.item?.standardJapanese ?: "current number"}"
        setOnClickListener { onReplay() }
        addView(AppIconView(context, AppIcon.VOLUME, 38).apply {
            setIconColor(Color.WHITE)
        }, FrameLayout.LayoutParams(38.dp(), 38.dp(), Gravity.CENTER))
    }

    private fun numberProgress(currentIndex: Int) = GridLayout(context).apply {
        columnCount = 5
        rowCount = 2
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
        setPadding(0, 2.dp(), 0, 2.dp())
        JapaneseCountLesson.items.forEachIndexed { index, item ->
            val completed = index < currentIndex
            val current = index == currentIndex
            addView(FrameLayout(context).apply {
                addView(TextView(context).apply {
                    text = item.number
                    textSize = 15f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(if (current) Color.WHITE else if (completed) red else ink)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (current) red else if (completed) paleRed else Color.TRANSPARENT)
                        setStroke(1.dp(), if (current || completed) red else border)
                    }
                    contentDescription = when {
                        current -> "${item.number}, current"
                        completed -> "${item.number}, completed"
                        else -> "${item.number}, upcoming"
                    }
                }, FrameLayout.LayoutParams(42.dp(), 42.dp(), Gravity.CENTER))
            }, GridLayout.LayoutParams(
                GridLayout.spec(index / 5),
                GridLayout.spec(index % 5, 1f),
            ).apply {
                width = 0
                height = 56.dp()
                setMargins(3.dp(), 3.dp(), 3.dp(), 3.dp())
            })
        }
    }

    private fun completionContent() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(KarateCharacterView(
                context = context,
                faceVariant = DEFAULT_FACE_VARIANT,
                beltRank = KarateBeltRank.WHITE,
            ), LinearLayout.LayoutParams(116.dp(), 202.dp()).apply { marginEnd = 14.dp() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("You completed the full set", 18f, Typeface.BOLD).apply { setTextColor(red) })
                addView(label(
                    "You’ve heard each number, practiced the short dojo pronunciation, and worked through the full set from 1 to 10.",
                    15f,
                ).apply { setPadding(0, 7.dp(), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 15.dp(), 14.dp(), 15.dp())
            addView(AppIconView(context, AppIcon.MICROPHONE, 34).apply { setIconColor(red) },
                LinearLayout.LayoutParams(40.dp(), 48.dp()).apply { marginEnd = 12.dp() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Next: Count from 1 to 10", 17f, Typeface.BOLD).apply { setTextColor(red) })
                addView(label(
                    "In the next activity, you’ll hear the full count once and then say all 10 numbers yourself. " +
                        "The app will listen and check the sequence.",
                    14f,
                ).apply { setPadding(0, 5.dp(), 0, 8.dp()) })
                addView(label("Microphone required", 12f, Typeface.BOLD, Gravity.CENTER).apply {
                    setTextColor(Color.WHITE)
                    background = roundedSurface(red, 10.dp())
                    setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, matchWrap().apply { topMargin = 12.dp() })
        addView(card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            addView(label("Are you ready?", 17f, Typeface.BOLD))
            addView(label("It’s completely fine to practice again before moving on.", 14f).apply {
                setPadding(0, 4.dp(), 0, 0)
            })
        }, matchWrap().apply {
            topMargin = 12.dp()
            bottomMargin = 4.dp()
        })
    }

    private fun card() = LinearLayout(context).apply {
        background = roundedSurface(warmSurface, 24.dp(), border)
        elevation = 2.dp().toFloat()
    }

    private fun divider() = FrameLayout(context).apply { setBackgroundColor(border) }

    private fun roundedSurface(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        stroke?.let { setStroke(1.dp(), it) }
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", style)
            this.gravity = gravity
            setLineSpacing(2.dp().toFloat(), 1f)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        /** Temporary default until an existing profile preference is introduced. */
        val DEFAULT_FACE_VARIANT = KarateFaceVariant.MALE
    }
}
