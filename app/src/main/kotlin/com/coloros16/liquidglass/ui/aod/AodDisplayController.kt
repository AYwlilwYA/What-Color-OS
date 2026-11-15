package com.coloros16.liquidglass.ui.aod

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import com.coloros16.liquidglass.engine.transitionEdgeOf
import com.coloros16.liquidglass.log.Lg
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

/** 显示设置：引擎驱动预览、六边直连、长按逐帧、样式草稿与三钮抽屉。 */
class AodDisplayController(
    private val context: Context,
    private val root: View,
    private val localPrefs: SharedPreferences,
    private val readString: (String, String) -> String,
    private val readBoolean: (String, Boolean) -> Boolean,
    private val writeString: (String, String) -> Unit,
    private val writeBoolean: (String, Boolean) -> Unit,
    private val close: () -> Unit,
) : AodStateBar.Listener {

    private companion object {
        const val TAG = "LgAodUi"
        const val DURATION_MS = 500L
    }

    private val density = context.resources.displayMetrics.density
    private val plate600 = context.getColor(R.color.lg_plate_600)
    private val seam = context.getColor(R.color.lg_seam)
    private val seamHi = context.getColor(R.color.lg_seam_hi)
    private val ink100 = context.getColor(R.color.lg_ink_100)
    private val ink300 = context.getColor(R.color.lg_ink_300)
    private val ink400 = context.getColor(R.color.lg_ink_400)
    private val brass = context.getColor(R.color.lg_brass)
    private val blued = context.getColor(R.color.lg_blued)
    private val bluedInk = context.getColor(R.color.lg_blued_ink)

    private val clock = TransitionClock()
    private val defaultHint = context.getString(R.string.settings_aod_page_hint)
    private val store = AodConfigStore(localPrefs)

    private lateinit var preview: AodPreviewView
    private lateinit var bar: AodStateBar
    private lateinit var hint: TextView
    private lateinit var badge: TextView
    private lateinit var apply: TextView
    private lateinit var cancel: TextView
    private lateinit var header: TextView

    private var bound = false
    private var current = SceneState.AOD
    private var lastEdge = TransitionEdge.AOD_TO_LOCK
    private var draftId = Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
    private var appliedId = Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
    private var draftConfigId: String? = null
    private var appliedConfigId: String? = null
    private var appliedEnabled = false
    private var wallpaperLoaded = false
    private var playing = false
    private var scrub = false
    private var frameScheduled = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!preview.isAttachedToWindow) {
                playing = false
                bar.setInteractive(true)
                return
            }
            if (clock.advance()) {
                frameScheduled = true
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                playing = false
                bar.setInteractive(true)
                bar.setActive(indexOf(current))
                preview.render(current, null, 0f)
            }
        }
    }

    fun bind() {
        if (bound) {
            refresh()
            return
        }
        preview = root.findViewById(R.id.aodPreview)
            ?: return missing("aodPreview")
        bar = root.findViewById(R.id.aodStateBar)
            ?: return missing("aodStateBar")
        hint = root.findViewById(R.id.aodHint)
            ?: return missing("aodHint")
        badge = root.findViewById(R.id.aodScrubBadge)
            ?: return missing("aodScrubBadge")
        apply = root.findViewById(R.id.aodApply)
            ?: return missing("aodApply")
        cancel = root.findViewById(R.id.aodCancel)
            ?: return missing("aodCancel")
        header = root.findViewById(R.id.aodHeaderLabel)
            ?: return missing("aodHeaderLabel")

        bound = true
        appliedConfigId = localPrefs.getString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, null)
        appliedId = store.find(appliedConfigId)?.styleId
            ?: styleOf(readString(Prefs.KEY_ALIVE_AOD_STYLE_ID, Prefs.DEFAULT_ALIVE_AOD_STYLE_ID)).id
        draftId = appliedId
        draftConfigId = appliedConfigId
        appliedEnabled = readBoolean(Prefs.KEY_ALIVE_AOD_STYLE, Prefs.DEFAULT_ALIVE_AOD_STYLE)

        preview.style = styleOf(draftId)
        bar.listener = this
        bar.setActive(0)
        clock.subscribe { edge, _, t, _, _ ->
            preview.render(current, edge, t)
            if (scrub && edge != null) {
                badge.text = edgeText(edge, t)
            }
        }

        cancel.setOnClickListener { cancelDraft() }
        apply.setOnClickListener { applyDraft() }
        root.findViewById<View>(R.id.aodDockWallpaper)?.setOnClickListener { showWallpaperSheet() }
        root.findViewById<View>(R.id.aodDockStyle)?.setOnClickListener { showStyleSheet() }
        root.findViewById<View>(R.id.aodDockEffects)?.setOnClickListener { showEffectsSheet() }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (playing) {
                    playing = false
                    clock.pause()
                    bar.setInteractive(true)
                }
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                frameScheduled = false
                preview.render(current, null, 0f)
            }
        })

        loadWallpaper()
        updateUi()
        Lg.i(TAG, "bind style=$draftId applied=$appliedId config=$appliedConfigId enabled=$appliedEnabled")
    }

    fun refresh() {
        if (!bound) return
        val newConfigId = localPrefs.getString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, null)
        val newApplied = store.find(newConfigId)?.styleId
            ?: styleOf(readString(Prefs.KEY_ALIVE_AOD_STYLE_ID, Prefs.DEFAULT_ALIVE_AOD_STYLE_ID)).id
        val wasClean = draftId == appliedId && draftConfigId == appliedConfigId
        appliedId = newApplied
        appliedConfigId = newConfigId
        if (wasClean) {
            draftId = newApplied
            draftConfigId = newConfigId
        }
        appliedEnabled = readBoolean(Prefs.KEY_ALIVE_AOD_STYLE, Prefs.DEFAULT_ALIVE_AOD_STYLE)
        updateUi()
    }

    override fun onTap(index: Int) {
        if (playing || scrub) {
            Lg.i(TAG, "tap ignored playing=$playing scrub=$scrub")
            return
        }
        val target = SceneState.values().getOrNull(index)
        if (target == null) {
            Lg.w(TAG, "tap ignored invalid index=$index")
            return
        }
        val edge = transitionEdgeOf(current, target)
        if (edge == null) {
            Lg.i(TAG, "tap same state=$target no animation")
            return
        }
        lastEdge = edge
        current = target
        bar.setActive(index)
        playing = true
        bar.setInteractive(false)
        clock.play(edge, DURATION_MS, 0f, ClockSource.PREVIEW)
        scheduleFrame()
        Lg.i(TAG, "play edge=$edge target=$target")
    }

    override fun onScrubStart() {
        if (playing) {
            Lg.i(TAG, "scrub ignored playing=true")
            return
        }
        scrub = true
        val edge = lastEdge
        val start = if (current == edge.to) 1f else 0f
        clock.seek(edge, start)
        bar.setScrub(true, start)
        badge.visibility = View.VISIBLE
        badge.text = edgeText(edge, start)
        hint.text = context.getString(R.string.settings_aod_scrub_hint)
        Lg.i(TAG, "scrub start edge=$edge t=$start")
    }

    override fun onScrubMove(t: Float) {
        if (!scrub) return
        clock.seek(lastEdge, t)
        badge.text = edgeText(lastEdge, t)
    }

    override fun onScrubEnd(t: Float) {
        if (!scrub) return
        scrub = false
        val edge = lastEdge
        val settleTo = t >= 0.5f
        current = if (settleTo) edge.to else edge.from
        clock.seek(edge, if (settleTo) 1f else 0f)
        bar.setScrub(false, 0f)
        bar.setActive(indexOf(current))
        badge.visibility = View.GONE
        hint.text = defaultHint
        preview.render(current, null, 0f)
        Lg.i(TAG, "scrub end t=$t state=$current")
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun updateUi() {
        val style = styleOf(draftId)
        preview.style = style
        preview.useSystemWallpaper = readBoolean(
            Prefs.KEY_ALIVE_AOD_WALLPAPER,
            Prefs.DEFAULT_ALIVE_AOD_WALLPAPER,
        )
        header.text = context.getString(style.nameRes)
        bar.setActive(indexOf(current))
        val dirty = draftId != appliedId || draftConfigId != appliedConfigId
        val canApply = dirty || !appliedEnabled
        apply.isEnabled = canApply
        apply.alpha = if (canApply) 1f else 0.45f
        cancel.isEnabled = true
        cancel.alpha = 1f
        preview.render(current, null, 0f)
    }

    private fun applyDraft() {
        writeString(Prefs.KEY_ALIVE_AOD_STYLE_ID, draftId)
        writeBoolean(Prefs.KEY_ALIVE_AOD_STYLE, true)
        val configId = draftConfigId
        if (configId == null) {
            localPrefs.edit().remove(Prefs.KEY_ALIVE_AOD_CONFIG_ID).apply()
        } else {
            localPrefs.edit().putString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, configId).apply()
        }
        appliedId = draftId
        appliedConfigId = configId
        appliedEnabled = true
        updateUi()
        toast(context.getString(R.string.settings_aod_applied))
        Lg.i(TAG, "apply style=$draftId config=$configId")
        close()
    }

    private fun cancelDraft() {
        draftId = appliedId
        draftConfigId = appliedConfigId
        updateUi()
        toast(context.getString(R.string.settings_aod_restored))
        Lg.i(TAG, "cancel draft -> $appliedId config=$appliedConfigId")
        close()
    }

    private fun showStyleSheet() {
        val configs = store.list()
        AodStyleSheet(
            context = context,
            recentConfigs = configs,
            selectedConfigId = draftConfigId,
            onPickConfig = { config -> pickConfig(config) },
            onPickTemplate = { style -> pickTemplate(style) },
            onImport = { toast(context.getString(R.string.settings_aod_import_unavailable)) },
        ).show()
        Lg.i(TAG, "style sheet open configs=${configs.size} selected=$draftConfigId")
    }

    private fun pickConfig(config: AodConfig) {
        draftId = config.styleId
        draftConfigId = config.id
        updateUi()
        Lg.i(TAG, "pick config=${config.id} style=$draftId")
    }

    private fun pickTemplate(style: AodStyle) {
        val name = nextConfigName(style)
        val config = store.add(style.id, name)
        draftId = style.id
        draftConfigId = config.id
        updateUi()
        Lg.i(TAG, "pick template=${style.id} new config=${config.id} name=$name")
    }

    private fun nextConfigName(style: AodStyle): String {
        val configs = store.list()
        val base = context.getString(style.nameRes)
        var index = configs.count { it.styleId == style.id } + 1
        while (configs.any { it.name == "$base $index" }) index++
        return "$base $index"
    }

    private fun showEffectsSheet() {
        val (dialog, content) = buildSheet(
            context.getString(R.string.settings_aod_effects_title),
            context.getString(R.string.settings_aod_effects_desc),
        )
        addSwitchRow(
            content,
            context.getString(R.string.settings_alive_aod_handoff),
            context.getString(R.string.settings_alive_aod_handoff_desc),
            Prefs.KEY_ALIVE_AOD_HANDOFF,
            Prefs.DEFAULT_ALIVE_AOD_HANDOFF,
        ) { checked ->
            writeBoolean(Prefs.KEY_ALIVE_AOD_HANDOFF, checked)
            Lg.i(TAG, "effect handoff=$checked")
        }
        addDivider(content)
        addSwitchRow(
            content,
            context.getString(R.string.settings_alive_aod_style),
            context.getString(R.string.settings_alive_aod_style_desc),
            Prefs.KEY_ALIVE_AOD_STYLE,
            Prefs.DEFAULT_ALIVE_AOD_STYLE,
        ) { checked ->
            writeBoolean(Prefs.KEY_ALIVE_AOD_STYLE, checked)
            appliedEnabled = checked
            updateUi()
            Lg.i(TAG, "effect style=$checked")
        }
        addDivider(content)
        addSwitchRow(
            content,
            context.getString(R.string.settings_alive_aod_glass),
            context.getString(R.string.settings_alive_aod_glass_desc),
            Prefs.KEY_ALIVE_AOD_GLASS,
            Prefs.DEFAULT_ALIVE_AOD_GLASS,
        ) { checked ->
            writeBoolean(Prefs.KEY_ALIVE_AOD_GLASS, checked)
            Lg.i(TAG, "effect glass=$checked")
        }
        dialog.show()
    }

    private fun showWallpaperSheet() {
        val (dialog, content) = buildSheet(
            context.getString(R.string.settings_aod_wallpaper_title),
            context.getString(R.string.settings_aod_wallpaper_desc),
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(8f))
        }
        val tiles = listOf(
            Triple(context.getString(R.string.settings_aod_wallpaper_static), intArrayOf(0xFF2D4A72.toInt(), 0xFF101A2A.toInt()), true),
            Triple(context.getString(R.string.settings_aod_wallpaper_live), intArrayOf(0xFF1E6A72.toInt(), 0xFF0D2026.toInt()), false),
            Triple(context.getString(R.string.settings_aod_wallpaper_import), intArrayOf(0xFF6A5320.toInt(), 0xFF241C0C.toInt()), null),
        )
        for ((label, colors, takeover) in tiles) {
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dpi(4f)
                    rightMargin = dpi(4f)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (takeover == null) {
                        toast(context.getString(R.string.settings_aod_wallpaper_unavailable))
                    } else {
                        writeBoolean(Prefs.KEY_ALIVE_AOD_WALLPAPER, takeover)
                        updateUi()
                        toast(context.getString(
                            if (takeover) R.string.settings_aod_wallpaper_takeover_on
                            else R.string.settings_aod_wallpaper_takeover_off,
                        ))
                        Lg.i(TAG, "wallpaper takeover=$takeover")
                    }
                    dialog.dismiss()
                }
            }
            tile.addView(
                View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                        cornerRadius = dp(12f)
                        setStroke(dpi(1f), seamHi)
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(92f)),
            )
            tile.addView(
                TextView(context).apply {
                    text = label
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    setTextColor(ink300)
                    setPadding(0, dpi(6f), 0, 0)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            row.addView(tile)
        }
        content.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dialog.show()
        Lg.i(TAG, "wallpaper sheet open")
    }

    private fun loadWallpaper() {
        if (wallpaperLoaded) return
        wallpaperLoaded = true
        Thread {
            val bitmap = try {
                val drawable = WallpaperManager.getInstance(context).getDrawable() ?: return@Thread
                val sourceW = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
                val sourceH = drawable.intrinsicHeight.takeIf { it > 0 } ?: 2400
                val targetW = 1080
                val targetH = (sourceH.toFloat() * targetW / sourceW).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = Canvas(target)
                    drawable.setBounds(0, 0, targetW, targetH)
                    drawable.draw(canvas)
                }
            } catch (t: Throwable) {
                Lg.w(TAG, "wallpaper load failed ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (bitmap != null) {
                preview.post { preview.setWallpaper(bitmap) }
            }
        }.apply { name = "aod-wallpaper"; isDaemon = true }.start()
    }

    private fun buildSheet(title: String, desc: String): Pair<BottomSheetDialog, LinearLayout> {
        val dialog = BottomSheetDialog(context)
        val scroll = NestedScrollView(context).apply {
            isFillViewport = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
                setColor(context.getColor(R.color.lg_plate_700))
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_aod_sheet)
            setPadding(0, dpi(8f), 0, dpi(20f))
        }
        scroll.addView(content)
        content.addView(
            View(context).apply { setBackgroundResource(R.drawable.bg_aod_grip) },
            LinearLayout.LayoutParams(dpi(38f), dpi(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpi(2f)
                bottomMargin = dpi(10f)
            },
        )
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dpi(16f), 0, dpi(16f), dpi(10f))
        }
        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(
            TextView(context).apply {
                text = title
                textSize = 17f
                setTextColor(ink100)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        titles.addView(
            TextView(context).apply {
                text = desc
                textSize = 10.5f
                letterSpacing = 0.02f
                setTextColor(ink400)
                typeface = Typeface.MONOSPACE
                setPadding(0, dpi(6f), 0, 0)
            },
        )
        head.addView(titles)
        val close = TextView(context).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ink300)
            setBackgroundResource(R.drawable.bg_aod_close)
            contentDescription = context.getString(R.string.settings_aod_sheet_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(close, LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
        content.addView(
            head,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        return dialog to content
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        desc: String,
        key: String,
        default: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ink100)
            },
        )
        texts.addView(
            TextView(context).apply {
                text = desc
                textSize = 10.5f
                setTextColor(ink400)
                setPadding(0, dpi(3f), dpi(10f), 0)
            },
        )
        row.addView(texts)
        val toggle = Switch(context).apply {
            text = ""
            isChecked = readBoolean(key, default)
            setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_thumb))
            setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_track))
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(toggle)
        parent.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(
            View(context).apply { setBackgroundColor(seam) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1f)).apply {
                leftMargin = dpi(16f)
                rightMargin = dpi(16f)
            },
        )
    }

    private fun missing(id: String) {
        Lg.w(TAG, "bind skipped: missing view $id")
    }

    private fun indexOf(state: SceneState): Int = when (state) {
        SceneState.AOD -> 0
        SceneState.LOCK -> 1
        SceneState.HOME -> 2
    }

    private fun styleOf(id: String): AodStyle = AodStyleCatalog.find(id)

    private fun edgeText(edge: TransitionEdge, t: Float): String =
        stateName(edge.from) + " → " + stateName(edge.to) + " · " + (t * 100f).roundToInt() + "%"

    private fun stateName(state: SceneState): String = context.getString(
        when (state) {
            SceneState.AOD -> R.string.settings_aod_state_aod
            SceneState.LOCK -> R.string.settings_aod_state_lock
            SceneState.HOME -> R.string.settings_aod_state_home
        },
    )

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Float): Float = value * density

    private fun dpi(value: Float): Int = (value * density).toInt()
}
