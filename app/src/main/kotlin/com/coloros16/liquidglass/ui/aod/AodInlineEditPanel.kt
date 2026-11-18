package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.ClockStyle
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.PillType
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.edit.WidgetSize
import com.coloros16.liquidglass.log.Lg
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 预览页内联编辑的底部抽屉：时钟与颜色 / 添加小组件 / 大丸调整。 */
class AodInlineEditPanel(
    private val context: Context,
    private val onChanged: () -> Unit,
) {

    private companion object {
        const val TAG = "LgAodEdit"
        const val STYLE_DEFAULT = "clock.default"
        const val STYLE_THIN = "clock.thin"
        const val STYLE_BOLD = "clock.bold"
        const val STYLE_SERIF = "clock.serif"
    }

    private val density = context.resources.displayMetrics.density
    private val ink100 = context.getColor(R.color.lg_ink_100)
    private val ink300 = context.getColor(R.color.lg_ink_300)
    private val ink400 = context.getColor(R.color.lg_ink_400)
    private val bluedInk = context.getColor(R.color.lg_blued_ink)

    private var dialog: BottomSheetDialog? = null
    private var settings: LockLayoutSettings? = null
    private var fontChips: List<Pair<String, TextView>> = emptyList()
    private var colorChips: List<Pair<Int, TextView>> = emptyList()
    private var infoChips: List<Pair<ClockInfo, TextView>> = emptyList()
    private var styleChips: List<Pair<String, TextView>> = emptyList()
    private var weightSeek: SeekBar? = null
    private var glassSwitch: Switch? = null

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** 时钟与颜色：字体 / 笔画粗细 / 颜色（玻璃染色）/ 附加信息 / 样式预设。 */
    fun showClockPanel(layout: LockLayoutSettings) {
        settings = layout
        val (sheet, content) = buildSheet(context.getString(R.string.settings_aod_edit_clock_panel))

        content.addView(groupLabel(R.string.settings_aod_edit_font))
        fontChips = chipRow(
            content,
            listOf(
                ClockFonts.SANS to context.getString(R.string.settings_aod_edit_font_sans),
                ClockFonts.SERIF to context.getString(R.string.settings_aod_edit_font_serif),
                ClockFonts.MONO to context.getString(R.string.settings_aod_edit_font_mono),
                ClockFonts.CASUAL to context.getString(R.string.settings_aod_edit_font_casual),
            ),
        ) { family -> applyClock(layout.clock.copy(fontFamily = family)) }

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
        ) { argb -> applyClock(layout.clock.copy(colorArgb = argb)) }

        val glass = Switch(context).apply {
            text = context.getString(R.string.settings_aod_edit_glass_tint)
            setTextColor(ink100)
            textSize = 14f
            isChecked = layout.clock.glassTint
            setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_thumb))
            setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_track))
            setOnCheckedChangeListener { _, checked ->
                applyClock(layout.clock.copy(glassTint = checked))
            }
        }
        glassSwitch = glass
        content.addView(glass)
        content.addView(
            TextView(context).apply {
                text = context.getString(R.string.settings_aod_edit_glass_tint_desc)
                setTextColor(ink400)
                textSize = 11.5f
            },
        )

        content.addView(groupLabel(R.string.settings_aod_edit_info))
        infoChips = chipRow(
            content,
            listOf(
                ClockInfo.NONE to context.getString(R.string.settings_aod_edit_info_none),
                ClockInfo.LUNAR to context.getString(R.string.settings_aod_edit_info_lunar),
                ClockInfo.WEATHER to context.getString(R.string.settings_aod_edit_info_weather),
            ),
        ) { info -> applyClock(layout.clock.copy(info = info)) }

        content.addView(groupLabel(R.string.settings_aod_edit_style))
        styleChips = chipRow(
            content,
            listOf(
                STYLE_DEFAULT to context.getString(R.string.settings_aod_edit_style_default),
                STYLE_THIN to context.getString(R.string.settings_aod_edit_style_thin),
                STYLE_BOLD to context.getString(R.string.settings_aod_edit_style_bold),
                STYLE_SERIF to context.getString(R.string.settings_aod_edit_style_serif),
            ),
        ) { styleId -> applyClock(presetOf(layout, styleId)) }

        content.addView(groupLabel(R.string.settings_aod_edit_weight))
        val weightRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weightRow.addView(
            TextView(context).apply { text = "A"; textSize = 12f; setTextColor(ink400) },
        )
        val seek = SeekBar(context).apply {
            max = 8
            progress = ((layout.clock.weight - 100) / 100).coerceIn(0, 8)
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.lg_seek_track)
            thumb = ContextCompat.getDrawable(context, R.drawable.lg_seek_thumb)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    applyClock(layout.clock.copy(weight = (progress + 1) * 100))
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        weightSeek = seek
        weightRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        weightRow.addView(
            TextView(context).apply { text = "A"; textSize = 20f; setTextColor(ink100); typeface = Typeface.DEFAULT_BOLD },
        )
        content.addView(weightRow)

        sheet.show()
        syncChips()
        Lg.i(TAG, "inline clock panel open")
    }

    /** 添加小组件：目录网格，点选添加/移除；已添加的显示尺寸并可长按改档。 */
    fun showWidgetPicker(layout: LockLayoutSettings) {
        settings = layout
        val (sheet, content) = buildSheet(context.getString(R.string.settings_aod_edit_widget_picker))
        var row: LinearLayout? = null
        for ((index, def) in WidgetCatalog.all.withIndex()) {
            if (index % 4 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                content.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (index == 0) 0 else dpi(10f)
                    },
                )
            }
            val added = layout.widget(def.id) != null
            val nameRes = context.resources.getIdentifier(def.nameKey, "string", context.packageName)
            val label = if (nameRes != 0) context.getString(nameRes) else def.id
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpi(4f)
                    marginEnd = dpi(4f)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (layout.widget(def.id) != null) {
                        layout.removeWidget(def.id)
                    } else {
                        WidgetCatalog.create(def.id)?.let { layout.setWidget(it) }
                    }
                    onChanged()
                    dismiss()
                    showWidgetPicker(layout)
                }
                setOnLongClickListener {
                    showWidgetMenu(def.id, layout)
                    true
                }
            }
            card.addView(
                TextView(context).apply {
                    text = if (added) "✓" else "+"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(if (added) bluedInk else ink300)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14f)
                        setColor(if (added) 0x263E6FD1 else 0x14FFFFFF)
                        setStroke(dpi(1f), if (added) bluedInk else 0x1FFFFFFF)
                    }
                },
                LinearLayout.LayoutParams(dpi(56f), dpi(56f)),
            )
            card.addView(
                TextView(context).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(ink100)
                    maxLines = 1
                    setPadding(0, dpi(6f), 0, 0)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            row!!.addView(card)
        }
        val tail = WidgetCatalog.all.size % 4
        if (tail != 0 && row != null) {
            repeat(4 - tail) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dpi(4f); marginEnd = dpi(4f) },
                )
            }
        }
        sheet.show()
        Lg.i(TAG, "inline widget picker open")
    }

    /** 小组件尺寸菜单：1×1 / 2×2 / 1×4 / 移除。 */
    fun showWidgetMenu(typeId: String, layout: LockLayoutSettings) {
        settings = layout
        val def = WidgetCatalog.find(typeId) ?: return
        val nameRes = context.resources.getIdentifier(def.nameKey, "string", context.packageName)
        val title = if (nameRes != 0) context.getString(nameRes) else typeId
        val (sheet, content) = buildSheet(title)
        val options = listOf(
            Triple(context.getString(R.string.settings_aod_edit_size_1x1), WidgetSize.SPAN_1X1, false),
            Triple(context.getString(R.string.settings_aod_edit_size_2x2), WidgetSize.SPAN_2X2, false),
            Triple(context.getString(R.string.settings_aod_edit_size_1x4), WidgetSize.SPAN_1X4, false),
            Triple(context.getString(R.string.settings_aod_edit_widget_remove), WidgetSize.SPAN_1X1, true),
        )
        val current = layout.widget(typeId)
        for ((label, size, remove) in options) {
            val selected = !remove && current?.size == size
            content.addView(sheetRow(label, selected) {
                if (remove) {
                    layout.removeWidget(typeId)
                } else {
                    val base = current ?: WidgetCatalog.create(typeId) ?: return@sheetRow
                    layout.setWidget(base.copy(size = size))
                }
                onChanged()
                dismiss()
            })
        }
        sheet.show()
        Lg.i(TAG, "inline widget menu type=$typeId")
    }

    /** 大丸调整：大小滑杆 + 显示开关。 */
    fun showPillPanel(typeId: String, layout: LockLayoutSettings) {
        settings = layout
        val pill = layout.pill(typeId) ?: return
        val nameRes = PillType.from(typeId)?.nameKey?.let { key ->
            context.resources.getIdentifier(key, "string", context.packageName)
        }?.takeIf { it != 0 } ?: R.string.settings_aod_edit_pill_flashlight
        val (sheet, content) = buildSheet(context.getString(nameRes))
        content.addView(groupLabel(R.string.settings_aod_edit_pill_content))
        val options = PillType.choices().map { type ->
            type.id to context.resources.getIdentifier(type.nameKey, "string", context.packageName)
        }
        var row: LinearLayout? = null
        for ((i, opt) in options.withIndex()) {
            if (i % 4 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dpi(8f) })
            }
            val (cid, labelRes) = opt
            val selected = layout.pillContent(typeId) == cid
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    layout.setPillContent(typeId, cid)
                    onChanged()
                    dismiss()
                    showPillPanel(typeId, layout)
                }
            }
            cell.addView(
                TextView(context).apply {
                    text = context.getString(labelRes)
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) bluedInk else ink100)
                    maxLines = 1
                    background = GradientDrawable().apply {
                        cornerRadius = dp(28f)
                        setColor(if (selected) 0x263E6FD1 else 0x14FFFFFF)
                        setStroke(dpi(if (selected) 1.5f else 1f), if (selected) bluedInk else 0x1FFFFFFF)
                    }
                    setPadding(dpi(4f), dpi(16f), dpi(4f), dpi(16f))
                },
                LinearLayout.LayoutParams(dpi(56f), dpi(56f)),
            )
            row!!.addView(cell)
        }
        content.addView(groupLabel(R.string.settings_aod_edit_pill_size))
        val label = TextView(context).apply {
            text = context.getString(R.string.settings_aod_edit_pill_scale, (pill.scale * 100).toInt())
            setTextColor(ink100)
            textSize = 13f
        }
        content.addView(label)
        val seek = SeekBar(context).apply {
            max = 100
            progress = ((pill.scale - PillPlacement.MIN_SCALE) /
                (PillPlacement.MAX_SCALE - PillPlacement.MIN_SCALE) * 100f).toInt().coerceIn(0, 100)
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.lg_seek_track)
            thumb = ContextCompat.getDrawable(context, R.drawable.lg_seek_thumb)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = PillPlacement.MIN_SCALE +
                        (PillPlacement.MAX_SCALE - PillPlacement.MIN_SCALE) * progress / 100f
                    layout.setPill(pill.copy(scale = scale))
                    label.text = context.getString(R.string.settings_aod_edit_pill_scale, (scale * 100).toInt())
                    onChanged()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        content.addView(seek)
        sheet.show()
        Lg.i(TAG, "inline pill panel type=$typeId")
    }

    private fun applyClock(style: ClockStyle) {
        val layout = settings ?: return
        layout.clock = style
        onChanged()
        syncChips()
    }

    private fun syncChips() {
        val layout = settings ?: return
        for ((family, chip) in fontChips) chip.background = chipBackground(family == layout.clock.fontFamily)
        for ((argb, chip) in colorChips) chip.background = chipBackground(argb == layout.clock.colorArgb)
        for ((info, chip) in infoChips) chip.background = chipBackground(info == layout.clock.info)
        for ((styleId, chip) in styleChips) chip.background = chipBackground(styleId == layout.clock.styleId)
        weightSeek?.progress = ((layout.clock.weight - 100) / 100).coerceIn(0, 8)
        glassSwitch?.setOnCheckedChangeListener(null)
        glassSwitch?.isChecked = layout.clock.glassTint
        glassSwitch?.setOnCheckedChangeListener { _, checked ->
            applyClock(layout.clock.copy(glassTint = checked))
        }
    }

    private fun presetOf(layout: LockLayoutSettings, styleId: String): ClockStyle = when (styleId) {
        STYLE_THIN -> layout.clock.copy(styleId = styleId, fontFamily = ClockFonts.SANS, weight = 200, letterSpacing = 0.02f)
        STYLE_BOLD -> layout.clock.copy(styleId = styleId, fontFamily = ClockFonts.SANS, weight = 800, letterSpacing = -0.01f)
        STYLE_SERIF -> layout.clock.copy(styleId = styleId, fontFamily = ClockFonts.SERIF, weight = 400, letterSpacing = 0f)
        else -> layout.clock.copy(styleId = styleId, fontFamily = ClockFonts.SANS, weight = 300, letterSpacing = 0f)
    }

    private fun buildSheet(title: String): Pair<BottomSheetDialog, LinearLayout> {
        dismiss()
        val sheet = BottomSheetDialog(context)
        val scroll = ScrollView(context).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
                setColor(context.getColor(R.color.lg_plate_700))
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(10f), dpi(20f), dpi(24f))
        }
        content.addView(
            View(context).apply { setBackgroundResource(R.drawable.bg_aod_grip) },
            LinearLayout.LayoutParams(dpi(38f), dpi(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpi(10f)
            },
        )
        content.addView(
            TextView(context).apply {
                text = title
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(ink100)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dpi(6f))
            },
        )
        scroll.addView(content)
        sheet.setContentView(scroll)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        }
        sheet.setOnDismissListener { dialog = null }
        dialog = sheet
        return sheet to content
    }

    private fun sheetRow(label: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4f), dpi(13f), dpi(4f), dpi(13f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 14.5f
                setTextColor(if (selected) bluedInk else ink100)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (selected) {
            row.addView(
                TextView(context).apply { text = "✓"; textSize = 14f; setTextColor(bluedInk) },
            )
        }
        return row
    }

    private fun groupLabel(textRes: Int): TextView = TextView(context).apply {
        text = context.getString(textRes)
        setTextColor(ink400)
        textSize = 12f
        setPadding(0, dpi(12f), 0, dpi(4f))
    }

    private fun <T> chipRow(
        container: LinearLayout,
        options: List<Pair<T, String>>,
        onPick: (T) -> Unit,
    ): List<Pair<T, TextView>> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpi(2f), 0, dpi(4f))
        }
        val chips = ArrayList<Pair<T, TextView>>(options.size)
        for ((value, label) in options) {
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(ink100)
                setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
                background = chipBackground(false)
                setOnClickListener {
                    onPick(value)
                    syncChips()
                }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpi(8f)
                },
            )
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

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(15f)
        setColor(if (selected) bluedInk else 0x14FFFFFF)
        if (!selected) setStroke(dpi(1f), 0x33FFFFFF)
    }

    private fun dp(value: Float): Float = value * density

    private fun dpi(value: Float): Int = (value * density).toInt()
}