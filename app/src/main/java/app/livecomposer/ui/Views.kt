package app.livecomposer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** 纯代码构建界面的小工具（项目不依赖 AndroidX，便于在任意环境编译检查）。 */
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

internal fun Context.label(text: String, sizeSp: Float = 14f, color: Int = Color.WHITE): TextView =
    TextView(this).apply { this.text = text; textSize = sizeSp; setTextColor(color) }

internal fun Context.button(text: String, onClick: (View) -> Unit): Button =
    Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener(onClick)
    }

internal fun Context.edit(value: String, hint: String, number: Boolean = false, password: Boolean = false): EditText =
    EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setSingleLine(true)
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
    }

internal fun Context.check(text: String, value: Boolean, onChange: (Boolean) -> Unit): CheckBox =
    CheckBox(this).apply {
        this.text = text; isChecked = value; setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

internal fun <T> Context.spinner(items: List<T>, selected: Int, title: (T) -> String, onSelect: (T) -> Unit): Spinner =
    Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@spinner, android.R.layout.simple_spinner_item, items.map(title)) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE) }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected.coerceAtLeast(0))
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                onSelect(items[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

internal fun Context.row(vararg views: View): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

internal fun Context.column(): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }

internal fun Context.panelBackground() = GradientDrawable().apply {
    setColor(Color.argb(235, 24, 24, 28)); cornerRadius = dp(12).toFloat()
}
