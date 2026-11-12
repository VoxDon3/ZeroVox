package com.zerovox

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

val Int.dp: Int get() = this

fun Context.dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

object Palette {
    var dark = true
        private set
    var bgDeep = Color.parseColor("#05070D")
    var onAccent = Color.parseColor("#05070D")
    var neon = Color.parseColor("#22D3FF")
    var neonDeep = Color.parseColor("#0A84FF")
    var text = Color.parseColor("#EAFBFF")
    var textSecondary = Color.parseColor("#8FA9B8")
    var textDim = Color.parseColor("#5C7482")
    var danger = Color.parseColor("#FF5C7A")
    var success = Color.parseColor("#38F5B0")
    var glassStroke = Color.parseColor("#3322D3FF")
    var cardTop = "#CC16202F"
    var cardMid = "#A60E1622"
    var cardBottom = "#99070C14"
    var softTop = "#99121A27"
    var softMid = "#A60E1622"
    var softBottom = "#66070C14"
    var softStroke = "#2422D3FF"
    var inputTop = "#80121A27"
    var inputMid = "#A60E1622"
    var inputBottom = "#66070C14"
    var inputStroke = "#3322D3FF"
    var outlineFill = "#1A22D3FF"
    var outlineStroke = "#AA22D3FF"
    var chipFill = "#66121A27"
    var chipStroke = "#3322D3FF"
    var chipSelectedStart = "#4422D3FF"
    var chipSelectedEnd = "#330A84FF"
    var chipSelectedStroke = "#CC22D3FF"
    var progressTrack = "#3322D3FF"
    var hairline = "#1F22D3FF"
    var connectedTop = "#3316C79A"
    var connectedMid = "#A60E1622"
    var connectedBottom = "#22107860"
    var connectedStroke = "#5538F5B0"
    var unpairedTop = "#66121A27"
    var unpairedMid = "#A60E1622"
    var unpairedBottom = "#44070C14"
    var unpairedStroke = "#2422D3FF"
    var coverTop = "#66121A27"
    var coverMid = "#A60E1622"
    var coverBottom = "#44070C14"

    fun applyTheme(light: Boolean) {
        dark = !light
        if (light) {
            bgDeep = Color.parseColor("#F2F7FB")
            onAccent = Color.parseColor("#041822")
            neon = Color.parseColor("#007C9E")
            neonDeep = Color.parseColor("#0069A8")
            text = Color.parseColor("#0B1B26")
            textSecondary = Color.parseColor("#3E5A6B")
            textDim = Color.parseColor("#6F8795")
            danger = Color.parseColor("#C6284A")
            success = Color.parseColor("#087F5B")
            glassStroke = Color.parseColor("#331E88A8")
            cardTop = "#E6FFFFFF"
            cardMid = "#D9FFFFFF"
            cardBottom = "#CCF7FAFD"
            softTop = "#E6FFFFFF"
            softMid = "#D9FFFFFF"
            softBottom = "#CCF7FAFD"
            softStroke = "#331E88A8"
            inputTop = "#F2FFFFFF"
            inputMid = "#E6FFFFFF"
            inputBottom = "#E6F7FAFD"
            inputStroke = "#331E88A8"
            outlineFill = "#1A007C9E"
            outlineStroke = "#AA007C9E"
            chipFill = "#E6FFFFFF"
            chipStroke = "#331E88A8"
            chipSelectedStart = "#3322D3FF"
            chipSelectedEnd = "#220A84FF"
            chipSelectedStroke = "#CC007C9E"
            progressTrack = "#331E88A8"
            hairline = "#331E88A8"
            connectedTop = "#3316C79A"
            connectedMid = "#E6FFFFFF"
            connectedBottom = "#CCE8F7F1"
            connectedStroke = "#5538F5B0"
            unpairedTop = "#E6FFFFFF"
            unpairedMid = "#D9FFFFFF"
            unpairedBottom = "#CCF7FAFD"
            unpairedStroke = "#331E88A8"
            coverTop = "#E6FFFFFF"
            coverMid = "#D9FFFFFF"
            coverBottom = "#CCF7FAFD"
        } else {
            bgDeep = Color.parseColor("#05070D")
            onAccent = Color.parseColor("#05070D")
            neon = Color.parseColor("#22D3FF")
            neonDeep = Color.parseColor("#0A84FF")
            text = Color.parseColor("#EAFBFF")
            textSecondary = Color.parseColor("#8FA9B8")
            textDim = Color.parseColor("#5C7482")
            danger = Color.parseColor("#FF5C7A")
            success = Color.parseColor("#38F5B0")
            glassStroke = Color.parseColor("#3322D3FF")
            cardTop = "#CC16202F"
            cardMid = "#A60E1622"
            cardBottom = "#99070C14"
            softTop = "#99121A27"
            softMid = "#A60E1622"
            softBottom = "#66070C14"
            softStroke = "#2422D3FF"
            inputTop = "#80121A27"
            inputMid = "#A60E1622"
            inputBottom = "#66070C14"
            inputStroke = "#3322D3FF"
            outlineFill = "#1A22D3FF"
            outlineStroke = "#AA22D3FF"
            chipFill = "#66121A27"
            chipStroke = "#3322D3FF"
            chipSelectedStart = "#4422D3FF"
            chipSelectedEnd = "#330A84FF"
            chipSelectedStroke = "#CC22D3FF"
            progressTrack = "#3322D3FF"
            hairline = "#1F22D3FF"
            connectedTop = "#3316C79A"
            connectedMid = "#A60E1622"
            connectedBottom = "#22107860"
            connectedStroke = "#5538F5B0"
            unpairedTop = "#66121A27"
            unpairedMid = "#A60E1622"
            unpairedBottom = "#44070C14"
            unpairedStroke = "#2422D3FF"
            coverTop = "#66121A27"
            coverMid = "#A60E1622"
            coverBottom = "#44070C14"
        }
    }
}

object Haptics {
    var enabled = true
}

fun glassDrawable(
    radius: Int,
    top: String = Palette.cardTop,
    bottom: String = Palette.cardBottom,
    stroke: Int = Palette.glassStroke,
    strokeWidth: Int = 1,
    mid: String = Palette.cardMid,
): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(Color.parseColor(top), Color.parseColor(mid), Color.parseColor(bottom))
).apply {
    cornerRadius = radius * 3f
    setStroke(strokeWidth, stroke)
}

fun outlineDrawable(radius: Int = 16): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius * 3f
    setColor(Color.parseColor(Palette.outlineFill))
    setStroke(1, Color.parseColor(Palette.outlineStroke))
}

fun neonDrawable(radius: Int = 16): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.LEFT_RIGHT,
    intArrayOf(Color.parseColor("#22D3FF"), Color.parseColor("#12B8FF"), Color.parseColor("#0A84FF"))
).apply {
    cornerRadius = radius * 3f
}

fun chipDrawable(selected: Boolean): GradientDrawable = if (selected) {
    GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor(Palette.chipSelectedStart), Color.parseColor(Palette.chipSelectedEnd))
    ).apply {
        cornerRadius = 60f
        setStroke(1, Color.parseColor(Palette.chipSelectedStroke))
    }
} else {
    GradientDrawable().apply {
        cornerRadius = 60f
        setColor(Color.parseColor(Palette.chipFill))
        setStroke(1, Color.parseColor(Palette.chipStroke))
    }
}

fun Context.titleText(text: String, size: Float = 22f): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Palette.text)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.01f
}

fun Context.bodyText(text: String, size: Float = 13f): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Palette.textSecondary)
    setLineSpacing(0f, 1.15f)
}

fun Context.accentText(text: String, size: Float = 12f): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Palette.neon)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.glassCard(padding: Int = 18): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = glassDrawable(24)
    setPadding(dpx(padding), dpx(padding), dpx(padding), dpx(padding))
}

fun Context.softCard(padding: Int = 14): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = glassDrawable(18, Palette.softTop, Palette.softBottom, Color.parseColor(Palette.softStroke), mid = Palette.softMid)
    setPadding(dpx(padding), dpx(padding), dpx(padding), dpx(padding))
}

fun Context.glassInput(hint: String, numeric: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    textSize = 15f
    setTextColor(Palette.text)
    setHintTextColor(Palette.textDim)
    background = glassDrawable(16, Palette.inputTop, Palette.inputBottom, Color.parseColor(Palette.inputStroke), mid = Palette.inputMid)
    setPadding(dpx(14), dpx(12), dpx(14), dpx(12))
    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    maxLines = 1
    filters = arrayOf(InputFilter.LengthFilter(120))
}

fun Context.codeInput(): EditText = glassInput(getString(R.string.pair_code_hint), numeric = true).apply {
    filters = arrayOf(InputFilter.LengthFilter(6))
    textSize = 20f
    letterSpacing = 0.3f
    gravity = Gravity.CENTER
}

fun View.pressAnimation() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Haptics.enabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                view.animate()
                    .scaleX(0.955f)
                    .scaleY(0.955f)
                    .alpha(0.82f)
                    .setDuration(90)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        false
    }
}

fun Context.actionButton(text: String, primary: Boolean, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Palette.onAccent else Palette.neon)
        background = if (primary) neonDrawable(16) else outlineDrawable(16)
        minHeight = dpx(44)
        minimumHeight = dpx(44)
        setPadding(dpx(16), dpx(8), dpx(16), dpx(8))
        stateListAnimator = null
        pressAnimation()
        setOnClickListener { onClick() }
    }

fun Context.progressBar(indeterminate: Boolean = false): ProgressBar = ProgressBar(
    this, null, android.R.attr.progressBarStyleHorizontal
).apply {
    max = 100
    isIndeterminate = indeterminate
    progressTintList = ColorStateList.valueOf(Palette.neon)
    progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor(Palette.progressTrack))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dpx(6)
    ).also { it.topMargin = dpx(10) }
}

fun Context.hairline(): View = View(this).apply {
    setBackgroundColor(Color.parseColor(Palette.hairline))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 1
    ).also { it.topMargin = dpx(12); it.bottomMargin = dpx(12) }
}

fun Context.spacer(height: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(height))
}
