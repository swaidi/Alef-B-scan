package app.screentextcopy

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Small helpers for building screens in code (same look as Riyaly). */
object Ui {
    val ALERT = Color.rgb(198, 40, 40)
}

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.attrColor(attr: Int, fallback: Int = Color.BLACK) = MaterialColors.getColor(this, attr, fallback)

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (pad > 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(child, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

fun Context.text(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int? = null): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(color ?: attrColor(com.google.android.material.R.attr.colorOnSurface))
    }

fun Context.muted(s: CharSequence, size: Float = 13f): TextView =
    text(s, size, color = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))

fun Context.section(s: CharSequence): TextView =
    text(s, 17f, true, attrColor(androidx.appcompat.R.attr.colorPrimary)).apply {
        setPadding(0, dp(18), 0, dp(6))
    }

fun Context.card(vararg children: View, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null): MaterialCardView =
    MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = attrColor(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
        val inner = vbox(16)
        children.forEach {
            inner.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(inner)
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
    }

fun Context.button(label: CharSequence, outlined: Boolean = false, onClick: () -> Unit): MaterialButton =
    (if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    else MaterialButton(this)).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

fun Context.chips(): ChipGroup = ChipGroup(this).apply {
    isSingleSelection = true
    isSelectionRequired = true
}

fun Context.chip(label: CharSequence, checked: Boolean, onClick: () -> Unit): Chip = Chip(this).apply {
    text = label
    isCheckable = true
    isChecked = checked
    setOnClickListener { onClick() }
}

fun Context.switchRow(label: CharSequence, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
    MaterialSwitch(this).apply {
        text = label
        isChecked = checked
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(4), 0, dp(4))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

/** A labelled slider; [onChange] is called when the finger is lifted. */
fun Context.slider(label: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit): View {
    val box = vbox()
    val title = text("$label: $value $unit", 14f)
    val bar = SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                title.text = "$label: $p $unit"
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress)
        })
    }
    box.addView(title)
    box.addView(bar)
    box.setPadding(0, dp(8), 0, 0)
    return box
}

fun Context.dialog(title: CharSequence) = MaterialAlertDialogBuilder(this).setTitle(title)

fun Context.confirm(title: CharSequence, message: CharSequence, yes: CharSequence, onYes: () -> Unit) {
    dialog(title).setMessage(message).setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton(R.string.cancel, null).show()
}

fun Context.toast(s: CharSequence) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
