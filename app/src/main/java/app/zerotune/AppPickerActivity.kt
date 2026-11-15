package app.zerotune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Список установленных приложений с галочками. Выбор сохраняется сразу. */
class AppPickerActivity : Activity() {

    private data class Item(val pkg: String, val label: String, val icon: Drawable)

    private val all = mutableListOf<Item>()
    private var shown: List<Item> = emptyList()
    private lateinit var selected: MutableSet<String>
    private lateinit var adapter: Adapter
    private lateinit var search: EditText
    private val pal: Palette by lazy { Theme.palette(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = Store.apps(this).toMutableSet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            setPadding(dp(20), dp(24), dp(20), 0)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.back)
            setTextColor(pal.accent)
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.picker_title)
            setTextColor(pal.textPrimary)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setHintTextColor(pal.textSecondary)
            setTextColor(pal.textPrimary)
            textSize = 14f
            background = Theme.input(context, pal)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(8)
        })

        adapter = Adapter()
        val list = ListView(this).apply {
            divider = null
            setSelector(android.R.color.transparent)
            this.adapter = this@AppPickerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos]) }
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Theme.applyWindow(this)

        // Загрузка списка в фоне — приложений может быть много
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != packageName }
                .map { (pkg, ri) -> Item(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                all.clear(); all.addAll(items)
                applyFilter(search.text.toString())
            }
        }.start()
    }

    private fun applyFilter(q: String) {
        val f = q.trim().lowercase()
        val base = if (f.isEmpty()) all else all.filter {
            it.label.lowercase().contains(f) || it.pkg.lowercase().contains(f)
        }
        // выбранные — наверх
        shown = base.sortedByDescending { it.pkg in selected }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(item: Item) {
        if (!selected.add(item.pkg)) selected.remove(item.pkg)
        Store.setApps(this, selected)
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = shown[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(context).apply { id = 1001 }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(context).apply {
                    id = 1002
                    setTextColor(pal.textPrimary)
                    textSize = 15f
                    isSingleLine = true
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(14); marginEnd = dp(8)
                })
                addView(CheckBox(context).apply {
                    id = 1003
                    isClickable = false
                    isFocusable = false
                    buttonTintList = ColorStateList.valueOf(pal.accent)
                })
            }
            row.findViewById<ImageView>(1001).setImageDrawable(item.icon)
            row.findViewById<TextView>(1002).text = item.label
            row.findViewById<CheckBox>(1003).isChecked = item.pkg in selected
            return row
        }
    }
}
