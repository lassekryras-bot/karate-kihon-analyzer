package dk.lasse.karatecliprecorder.learningactivity

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learning.JapaneseCountLesson

internal fun countingNumberProgress(context: Context, completedCount: Int, currentIndex: Int?) = GridLayout(context).apply {
        val red = ContextCompat.getColor(context, R.color.app_accent)
        val ink = ContextCompat.getColor(context, R.color.app_text_primary)
        val border = ContextCompat.getColor(context, R.color.app_border)
        val paleRed = ContextCompat.getColor(context, R.color.progress_pale_fill)
        fun Int.dp() = (this * resources.displayMetrics.density).toInt()
        columnCount = 5
        rowCount = 2
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
        setPadding(0, 2.dp(), 0, 2.dp())
        JapaneseCountLesson.items.forEachIndexed { index, item ->
            val completed = index < completedCount
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
                height = 46.dp()
                setMargins(3.dp(), 1.dp(), 3.dp(), 1.dp())
            })
        }
    }
