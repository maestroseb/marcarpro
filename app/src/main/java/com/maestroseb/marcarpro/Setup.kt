package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Getting Marcar Pro ready": every permission the layer can use, in one card, each with its
 * state and a button that opens the right screen. Shown on the first start and whenever the app
 * is opened from its icon with something still missing, so nothing has to be discovered later
 * feature by feature (the per-feature cards stay as a fallback).
 */
internal object Setup {

    class Item(
        val title: String,
        val why: String,
        val done: Boolean,
        /** Optional items don't keep the card coming back. */
        val optional: Boolean,
        val open: () -> Unit,
    )
}

/** The card itself: [items] is asked again on every [refresh] (states change in Settings). */
@SuppressLint("ViewConstructor")
internal class SetupView(
    context: Context,
    private val items: () -> List<Setup.Item>,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = TextView(context)
    private val close: TextView

    init {
        setBackgroundColor(0x99101418.toInt())
        isClickable = true
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(28f), dpi(24f), dpi(28f), dpi(18f))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(22f)
            }
            elevation = dp(12f)
            isClickable = true
        }
        title.apply {
            textSize = 22f
            setTextColor(OrbStyle.INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(title)
        card.addView(TextView(context).apply {
            text = context.getString(R.string.setup_intro)
            textSize = 16f
            setTextColor(OrbStyle.LABEL)
            setLineSpacing(0f, 1.2f)
        }, wide().apply { topMargin = dpi(8f) })
        card.addView(rows, wide().apply { topMargin = dpi(10f) })
        close = button("", filled = true) { onClose() }
        val bottom = LinearLayout(context).apply { gravity = Gravity.END }
        bottom.addView(close)
        card.addView(bottom, wide().apply { topMargin = dpi(18f) })
        addView(card, LayoutParams(dpi(620f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        refresh()
    }

    /** Reads the states again and redraws the rows. */
    fun refresh() {
        val list = items()
        val ready = list.all { it.done || it.optional }
        title.text = context.getString(if (ready) R.string.setup_ready else R.string.setup_title)
        close.text = context.getString(if (ready) R.string.setup_done else R.string.relay_later)
        rows.removeAllViews()
        for (item in list) rows.addView(row(item), wide().apply { topMargin = dpi(12f) })
    }

    private fun row(item: Setup.Item) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        // A tick when it's done, an empty ring when not.
        addView(TextView(context).apply {
            text = if (item.done) "✓" else ""
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (item.done) setColor(OrbStyle.TEAL) else setStroke(dpi(2f), 0xFFB8C2C0.toInt())
            }
        }, LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = if (item.optional) context.getString(R.string.setup_optional, item.title) else item.title
                textSize = 17f
                setTextColor(OrbStyle.INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = item.why
                textSize = 14f
                setTextColor(OrbStyle.LABEL)
            })
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpi(14f)
            marginEnd = dpi(12f)
        })
        if (!item.done) addView(button(context.getString(R.string.setup_open), filled = false) { item.open() })
    }

    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun button(label: String, filled: Boolean, onTap: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) Color.WHITE else OrbStyle.TEAL_DARK)
        setPadding(dpi(20f), dpi(10f), dpi(20f), dpi(10f))
        background = GradientDrawable().apply {
            setColor(if (filled) OrbStyle.TEAL else Color.WHITE)
            cornerRadius = dp(24f)
            if (!filled) setStroke(dpi(1.5f), OrbStyle.TEAL)
        }
        isClickable = true
        setOnClickListener { onTap() }
    }
}
