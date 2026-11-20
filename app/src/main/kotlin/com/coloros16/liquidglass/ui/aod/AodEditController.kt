package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.ClockStyle
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.PillType
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.edit.WidgetDef
import com.coloros16.liquidglass.engine.edit.WidgetSize
import com.coloros16.liquidglass.log.Lg

/** 锁屏 / 息屏编辑页：画布手势回调 → 布局配置落盘，控件区管理小组件与时钟外观。 */
class AodEditController(
    private val context: Context,
    private val root: View,
    private val surface: EditSurface,
    private val readLayout: (EditSurface) -> LockLayoutSettings,
    private val writeLayout: (EditSurface, LockLayoutSettings) -> Unit,
    private val openPreview: () -> Unit,
    /** 当前息屏样式 id：编辑页时钟锚点要跟预览取同一个样式契约。 */
    private val styleIdOf: () -> String,
) {

    private companion object {
        const val TAG = "LgAodEdit"
        const val STYLE_DEFAULT = "clock.default"
        const val STYLE_THIN = "clock.thin"
        const val STYLE_BOLD = "clock.bold"
        const val STYLE_SERIF = "clock.serif"
    }

    private var canvas: AodEditView? = null
    private var widgetBox: LinearLayout? = null
    private var snapSwitch: Switch? = null
    private var settings: LockLayoutSettings = LockLayoutSettings(surface)
    private var dialog: AlertDialog? = null
    private var fontChips: List<Pair<String, TextView>> = emptyList()
    private var colorChips: List<Pair<Int, TextView>> = emptyList()
    private var infoChips: List<Pair<ClockInfo, TextView>> = emptyList()
    private var styleChips: List<Pair<String, TextView>> = emptyList()
    private var pillChips: List<Pair<String, TextView>> = emptyList()
    private var weightSeek: SeekBar? = null
    private var weightValue: TextView? = null
    private var glassSwitch: Switch? = null

    private val density: Float get() = context.resources.displayMetrics.density

    fun bind() {
        settings = readLayout(surface)
        val view = root.findViewById<AodEditView>(R.id.aodEditCanvas) ?: return missing("aodEditCanvas")
        canvas = view
        view.surface = surface
        view.anchor = anchorOfSurface()
        view.settings = settings
        view.listener = object : AodEditView.Listener {
            override fun onClockTapped() = showClockPanel()

            override fun onWidgetHold(typeId: String) {
                val def = WidgetCatalog.find(typeId) ?: return
                sizeMenu(def)
            }

            override fun onWidgetTapped(typeId: String) {
                val def = WidgetCatalog.find(typeId) ?: return
                sizeMenu(def)
            }

            override fun onPillHold(typeId: String) = showPillPanel(typeId)

            override fun onPillTapped(typeId: String) = showPillPanel(typeId)

            override fun onEmptyTapped() = openPreview()

            override fun onLayoutEdited() = persist()
        }

        val hint = root.findViewById<TextView>(R.id.aodEditHint)
        hint?.setText(
            if (surface == EditSurface.AOD) R.string.settings_aod_edit_hint_aod else R.string.settings_aod_edit_hint_lock,
        )

        widgetBox = root.findViewById<LinearLayout>(R.id.aodEditWidgetBox)
        bindSnap()
        bindActions()
        rebuildWidgetRows()
        refreshSnap()
        Lg.i(TAG, "edit page bound surface=$surface")
    }

    fun refresh() {
        settings = readLayout(surface)
        canvas?.anchor = anchorOfSurface()
        canvas?.settings = settings
        canvas?.invalidate()
        rebuildWidgetRows()
        refreshSnap()
    }

    private fun bindSnap() {
        val toggle = root.findViewById<Switch>(R.id.aodEditSnap) ?: return missing("aodEditSnap")
        snapSwitch = toggle
        toggle.setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_thumb))
        toggle.setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_track))
        refreshSnap()
        toggle.setOnCheckedChangeListener { _, checked ->
            settings.snapEnabled = checked
            persist()
        }
    }

    private fun refreshSnap() {
        val toggle = snapSwitch ?: return
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = settings.snapEnabled
        toggle.setOnCheckedChangeListener { _, checked ->
            settings.snapEnabled = checked
            persist()
        }
    }

    private fun bindActions() {
        bindRow(R.id.aodEditPreviewRow, R.string.settings_aod_edit_preview, R.string.settings_aod_edit_preview_desc, R.drawable.ic_row_panel) {
            openPreview()
        }
        bindRow(R.id.aodEditResetRow, R.string.settings_aod_edit_reset, R.string.settings_aod_edit_reset_desc, R.drawable.ic_row_archive) {
            settings = LockLayoutSettings.defaults(surface)
            canvas?.settings = settings
            canvas?.invalidate()
            persist()
            rebuildWidgetRows()
            refreshSnap()
        }
    }

    private fun rebuildWidgetRows() {
        val box = widgetBox ?: return
        box.removeAllViews()
        for (def in WidgetCatalog.all) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_row, box, false)
            row.findViewById<ImageView>(R.id.rowIcon)?.setImageResource(iconOf(def.id))
            row.findViewById<TextView>(R.id.rowChevron)?.visibility = View.GONE
            row.findViewById<TextView>(R.id.rowTitle)?.text = widgetName(def)
            val widget = settings.widget(def.id)
            val value = row.findViewById<TextView>(R.id.rowValue)
            value?.apply {
                visibility = View.VISIBLE
                text = if (widget != null) {
                    context.getString(R.string.settings_aod_edit_widget_added, sizeLabel(widget.size))
                } else {
                    context.getString(R.string.settings_aod_edit_widget_add)
                }
            }
            row.setOnClickListener {
                if (settings.widget(def.id) != null) {
                    settings.removeWidget(def.id)
                } else {
                    WidgetCatalog.create(def.id)?.let { settings.setWidget(it) }
                }
                canvas?.invalidate()
                persist()
                rebuildWidgetRows()
            }
            row.setOnLongClickListener {
                sizeMenu(def)
                true
            }
            box.addView(row)
        }
    }

    private fun sizeMenu(def: WidgetDef) {
        val labels = arrayOf(
            context.getString(R.string.settings_aod_edit_size_1x1),
            context.getString(R.string.settings_aod_edit_size_2x2),
            context.getString(R.string.settings_aod_edit_size_1x4),
            context.getString(R.string.settings_aod_edit_widget_remove),
        )
        AlertDialog.Builder(context)
            .setTitle(widgetName(def))
            .setItems(labels) { _, which ->
                val base = settings.widget(def.id) ?: WidgetCatalog.create(def.id) ?: return@setItems
                when (which) {
                    0 -> settings.setWidget(base.copy(size = WidgetSize.SPAN_1X1))
                    1 -> settings.setWidget(base.copy(size = WidgetSize.SPAN_2X2))
                    2 -> settings.setWidget(base.copy(size = WidgetSize.SPAN_1X4))
                    else -> settings.removeWidget(def.id)
                }
                canvas?.invalidate()
                persist()
                rebuildWidgetRows()
            }
            .show()
    }

    private fun showPillPanel(typeId: String) {
        val pill = settings.pill(typeId) ?: return
        val nameRes = pillNameRes(typeId)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        box.addView(groupLabel(R.string.settings_aod_edit_pill_content))
        pillChips = chipRow(
            box,
            PillType.choices().map { it.id to context.getString(pillNameRes(it.id)) },
        ) { contentId ->
            settings.setPillContent(typeId, contentId)
            canvas?.invalidate()
            persist()
            for ((id, chip) in pillChips) {
                chip.background = chipBackground(id == settings.pillContent(typeId))
            }
        }
        for ((id, chip) in pillChips) {
            chip.background = chipBackground(id == settings.pillContent(typeId))
        }
        box.addView(groupLabel(R.string.settings_aod_edit_pill_size))
        val label = TextView(context).apply {
            text = context.getString(R.string.settings_aod_edit_pill_scale, (pill.scale * 100).toInt())
            setTextColor(context.getColor(R.color.lg_ink_100))
            textSize = 13f
        }
        val seek = SeekBar(context).apply {
            max = 100
            progress = ((pill.scale - PillPlacement.MIN_SCALE) /
                (PillPlacement.MAX_SCALE - PillPlacement.MIN_SCALE) * 100f).toInt().coerceIn(0, 100)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val scale = PillPlacement.MIN_SCALE +
                    (PillPlacement.MAX_SCALE - PillPlacement.MIN_SCALE) * progress / 100f
                settings.setPill(pill.copy(scale = scale))
                label.text = context.getString(R.string.settings_aod_edit_pill_scale, (scale * 100).toInt())
                canvas?.invalidate()
                persist()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        box.addView(label)
        box.addView(seek)
        AlertDialog.Builder(context)
            .setTitle(nameRes)
            .setView(box)
            .setNegativeButton(R.string.settings_aod_edit_close, null)
            .show()
    }

    private fun showClockPanel() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        content.addView(groupLabel(R.string.settings_aod_edit_font))
        fontChips = chipRow(
            content,
            listOf(
                ClockFonts.SANS to context.getString(R.string.settings_aod_edit_font_sans),
                ClockFonts.SERIF to context.getString(R.string.settings_aod_edit_font_serif),
                ClockFonts.MONO to context.getString(R.string.settings_aod_edit_font_mono),
                ClockFonts.CASUAL to context.getString(R.string.settings_aod_edit_font_casual),
            ),
        ) { family ->
            applyClock(settings.clock.copy(fontFamily = family))
        }

        content.addView(groupLabel(R.string.settings_aod_edit_weight))
        val weightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(context).apply {
            max = 8
            progress = ((settings.clock.weight - 100) / 100).coerceIn(0, 8)
        }
        val value = TextView(context).apply {
            text = settings.clock.weight.toString()
            setTextColor(context.getColor(R.color.lg_ink_100))
            textSize = 13f
        }
        weightSeek = seek
        weightValue = value
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = ((progress + 1) * 100).toString()
                applyClock(settings.clock.copy(weight = (progress + 1) * 100))
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        weightRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        weightRow.addView(value)
        content.addView(weightRow)

        content.addView(groupLabel(R.string.settings_aod_edit_color))
        colorChips = chipRow(
            content,
            listOf(
                0xFFFFFFFF.toInt() to context.getString(R.string.settings_aod_edit_color_white),
                0xFFFFF2D6.toInt() to context.getString(R.string.settings_aod_edit_color_warm),
                0xFF8FC3FF.toInt() to context.getString(R.string.settings_aod_edit_color_blue),
                0xFFFFB4C8.toInt() to context.getString(R.string.settings_aod_edit_color_pink),
                0xFFFFD166.toInt() to context.getString(R.string.settings_aod_edit_color_gold),
            ),
        ) { argb ->
            applyClock(settings.clock.copy(colorArgb = argb))
        }

        val glassRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val glass = Switch(context).apply {
            text = context.getString(R.string.settings_aod_edit_glass_tint)
            setTextColor(context.getColor(R.color.lg_ink_100))
            textSize = 14f
            isChecked = settings.clock.glassTint
            setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_thumb))
            setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_track))
        }
        glassSwitch = glass
        glass.setOnCheckedChangeListener { _, checked ->
            applyClock(settings.clock.copy(glassTint = checked))
        }
        glassRow.addView(glass)
        glassRow.addView(
            TextView(context).apply {
                text = context.getString(R.string.settings_aod_edit_glass_tint_desc)
                setTextColor(context.getColor(R.color.lg_ink_400))
                textSize = 12f
            },
        )
        content.addView(glassRow)

        content.addView(groupLabel(R.string.settings_aod_edit_info))
        infoChips = chipRow(
            content,
            listOf(
                ClockInfo.NONE to context.getString(R.string.settings_aod_edit_info_none),
                ClockInfo.LUNAR to context.getString(R.string.settings_aod_edit_info_lunar),
                ClockInfo.WEATHER to context.getString(R.string.settings_aod_edit_info_weather),
            ),
        ) { info ->
            applyClock(settings.clock.copy(info = info))
        }

        content.addView(groupLabel(R.string.settings_aod_edit_style))
        styleChips = chipRow(
            content,
            listOf(
                STYLE_DEFAULT to context.getString(R.string.settings_aod_edit_style_default),
                STYLE_THIN to context.getString(R.string.settings_aod_edit_style_thin),
                STYLE_BOLD to context.getString(R.string.settings_aod_edit_style_bold),
                STYLE_SERIF to context.getString(R.string.settings_aod_edit_style_serif),
            ),
        ) { styleId ->
            applyClock(presetOf(styleId))
        }

        val scroll = ScrollView(context).apply { addView(content) }
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_aod_edit_clock_panel)
            .setView(scroll)
            .setNegativeButton(R.string.settings_aod_edit_close, null)
            .show()
        syncChips()
    }

    private fun applyClock(style: ClockStyle) {
        settings.clock = style
        canvas?.invalidate()
        persist()
        syncChips()
    }

    private fun syncChips() {
        for ((family, chip) in fontChips) chip.background = chipBackground(family == settings.clock.fontFamily)
        for ((argb, chip) in colorChips) chip.background = chipBackground(argb == settings.clock.colorArgb)
        for ((info, chip) in infoChips) chip.background = chipBackground(info == settings.clock.info)
        for ((styleId, chip) in styleChips) chip.background = chipBackground(styleId == settings.clock.styleId)
        weightSeek?.progress = ((settings.clock.weight - 100) / 100).coerceIn(0, 8)
        weightValue?.text = settings.clock.weight.toString()
        glassSwitch?.setOnCheckedChangeListener(null)
        glassSwitch?.isChecked = settings.clock.glassTint
        glassSwitch?.setOnCheckedChangeListener { _, checked ->
            applyClock(settings.clock.copy(glassTint = checked))
        }
    }

    private fun <T> chipRow(
        container: LinearLayout,
        options: List<Pair<T, String>>,
        onPick: (T) -> Unit,
    ): List<Pair<T, TextView>> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val chips = ArrayList<Pair<T, TextView>>(options.size)
        for ((value, label) in options) {
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(context.getColor(R.color.lg_ink_100))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = chipBackground(false)
                setOnClickListener {
                    onPick(value)
                    syncChips()
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            row.addView(chip, params)
            chips.add(value to chip)
        }
        container.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            },
        )
        return chips
    }

    private fun pillNameRes(typeId: String): Int {
        val key = PillType.from(typeId)?.nameKey ?: return R.string.settings_aod_edit_pill_flashlight
        val res = context.resources.getIdentifier(key, "string", context.packageName)
        return if (res != 0) res else R.string.settings_aod_edit_pill_flashlight
    }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 15f * density
        setColor(if (selected) context.getColor(R.color.lg_blued_ink) else 0x14FFFFFF)
        if (!selected) setStroke((1f * density).toInt(), 0x33FFFFFF)
    }

    private fun presetOf(styleId: String): ClockStyle = when (styleId) {
        STYLE_THIN -> settings.clock.copy(styleId = styleId, fontFamily = ClockFonts.SANS, weight = 200, letterSpacing = 0.02f)
        STYLE_BOLD -> settings.clock.copy(styleId = styleId, fontFamily = ClockFonts.SANS, weight = 800, letterSpacing = -0.01f)
        STYLE_SERIF -> settings.clock.copy(styleId = styleId, fontFamily = ClockFonts.SERIF, weight = 400, letterSpacing = 0f)
        else -> settings.clock.copy(styleId = styleId, fontFamily = ClockFonts.SANS, weight = 300, letterSpacing = 0f)
    }

    private fun persist() {
        writeLayout(surface, settings)
    }

    /**
     * 该编辑面的样式时钟锚点：与预览取同一份契约，取不到契约时返回 null 走缺省位。
     *
     * 息屏面取 AOD 锚点、锁屏面取 LOCK 锚点；样式无世界契约（文字/人像等）本就没有锚点。
     */
    private fun anchorOfSurface(): Pair<Float, Float>? {
        val state = if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK
        val styleId = styleIdOf()
        val a = runCatching {
            AodSceneCatalog.forStyle(styleId)?.clockAnchors?.get(state)
        }.getOrNull()
        // 取的款与最终锚点必须能取证：两套存储并存时这里取错款只会表现为「位置不对」，没有别的痕迹
        Lg.i(TAG, "edit-anchor surface=$surface style=$styleId y=${a?.centerY ?: "(none)"}")
        if (a == null) return null
        return a.centerX to a.centerY
    }

    private fun bindRow(id: Int, title: Int, desc: Int, icon: Int, onClick: () -> Unit) {
        val row = root.findViewById<View>(id) ?: return missing(Integer.toHexString(id))
        row.findViewById<TextView>(R.id.rowTitle)?.text = context.getString(title)
        row.findViewById<TextView>(R.id.rowDesc)?.apply {
            text = context.getString(desc)
            visibility = View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.rowIcon)?.setImageResource(icon)
        row.setOnClickListener { onClick() }
    }

    private fun groupLabel(textRes: Int): TextView = TextView(context).apply {
        text = context.getString(textRes)
        setTextColor(context.getColor(R.color.lg_ink_400))
        textSize = 12f
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun widgetName(def: WidgetDef): String {
        val res = context.resources.getIdentifier(def.nameKey, "string", context.packageName)
        return if (res != 0) context.getString(res) else def.id
    }

    private fun sizeLabel(size: WidgetSize): String = "${size.columns}×${size.rows}"

    private fun iconOf(id: String): Int = when (id) {
        WidgetCatalog.WEATHER -> R.drawable.ic_row_cloud
        WidgetCatalog.MUSIC -> R.drawable.ic_row_pulse
        WidgetCatalog.BATTERY -> R.drawable.ic_row_phone
        WidgetCatalog.STEPS -> R.drawable.ic_row_person
        else -> R.drawable.ic_row_generic
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun missing(name: String) {
        Lg.w(TAG, "aod edit missing view $name")
    }

}
