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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectSettings
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
    private val readSettings: () -> com.coloros16.liquidglass.engine.effect.EffectSettings,
    private val writeSettings: (com.coloros16.liquidglass.engine.effect.EffectSettings) -> Unit,
    private val readLayout: (com.coloros16.liquidglass.engine.edit.EditSurface) -> com.coloros16.liquidglass.engine.edit.LockLayoutSettings,
    private val writeLayout: (com.coloros16.liquidglass.engine.edit.EditSurface, com.coloros16.liquidglass.engine.edit.LockLayoutSettings) -> Unit,
    private val pickPhoto: ((frame: Boolean) -> Unit)? = null,
    /** 面板内跳独立页（编辑画布）：先关面板再压屏，null 时只记日志不跳。 */
    private val openScreen: ((String) -> Unit)? = null,
) : AodStateBar.Listener {

    private companion object {
        const val TAG = "LgAodUi"
        const val AOD_OPEN_DELAY_MS = 150L
    }

    private val density = context.resources.displayMetrics.density
    private val ink100 = context.getColor(R.color.lg_ink_100)
    private val ink300 = context.getColor(R.color.lg_ink_300)
    private val ink400 = context.getColor(R.color.lg_ink_400)

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
    private lateinit var dockBar: LinearLayout

    private var bound = false
    private var dockState: SceneState? = null
    private var settingsSheet: BottomSheetDialog? = null
    private var editPanel: AodInlineEditPanel? = null
    private var scene3dActive = false
    private var scene3dCheckedAt = 0L
    private var current = SceneState.AOD
    private var draftId = Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
    private var appliedId = Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
    private var draftConfigId: String? = null
    private var appliedConfigId: String? = null
    private var appliedEnabled = false
    private var wallpaperLoaded = false
    private var playing = false
    private var scrub = false
    private var frameScheduled = false
    private var scrubP = 0f
    private var settling = false
    private var settleFrom = 0f
    private var settleTo = 0f
    private var settleStartMs = 0L
    private var settleScheduled = false

    private val settleCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            settleScheduled = false
            if (!settling || !preview.isAttachedToWindow) {
                settling = false
                return
            }
            val k = ((frameTimeNanos / 1_000_000L - settleStartMs) / 180f).coerceIn(0f, 1f)
            val eased = 1f - (1f - k) * (1f - k)
            val p = settleFrom + (settleTo - settleFrom) * eased
            scrubP = p
            preview.renderScrub(p)
            bar.setScrubT(p)
            if (k >= 1f) {
                settling = false
                finishScrub(settleTo)
            } else {
                settleScheduled = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

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
        dockBar = root.findViewById(R.id.aodDockBar)
            ?: return missing("aodDockBar")

        bound = true
        appliedConfigId = localPrefs.getString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, null)
        appliedId = styleOf(
            AodStyleSelection.resolve(
                appliedConfigId,
                { store.find(it)?.styleId },
                readString(Prefs.KEY_ALIVE_AOD_STYLE_ID, Prefs.DEFAULT_ALIVE_AOD_STYLE_ID),
            ),
        ).id
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
        rebuildDock()
        bindInlineEdit()

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                settling = false
                if (settleScheduled) {
                    Choreographer.getInstance().removeFrameCallback(settleCallback)
                    settleScheduled = false
                }
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
        if (playing || scrub || settling) {
            Lg.i(TAG, "tap ignored playing=$playing scrub=$scrub settling=$settling")
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
        val fromAod = current == SceneState.AOD
        current = target
        bar.setActive(index)
        rebuildDock()
        playing = true
        bar.setInteractive(false)
        val startDelay = if (fromAod) AOD_OPEN_DELAY_MS else 0L
        val effects = EffectSettings.parse(readString(Prefs.KEY_EFFECT_CONFIG, ""))
        val edgeMs = AodSceneCatalog.durationOf(draftId, edge, effects)
        preview.playbackEdgeMs = edgeMs
        clock.play(edge, edgeMs, 0f, ClockSource.PREVIEW, startDelay)
        scheduleFrame()
        Lg.i(TAG, "play edge=$edge target=$target delay=$startDelay dur=$edgeMs")
    }

    override fun onScrubStart() {
        if (playing) {
            Lg.i(TAG, "scrub ignored playing=true")
            return
        }
        scrub = true
        // 播放边长是「本次播放」的账本，拖动时边由 scrubP 决定、可能换成另一条
        // ⇒ 不清零就会拿上一条边的边长去归一化，曲线被按错的绝对时长拉伸。
        preview.playbackEdgeMs = 0L
        scrubP = SceneTimeline.anchor(current)
        bar.setScrub(true, scrubP)
        badge.visibility = View.VISIBLE
        badge.text = scrubLabel(scrubP)
        hint.text = context.getString(R.string.settings_aod_scrub_hint)
        Lg.i(TAG, "scrub start p=$scrubP")
    }

    override fun onScrubMove(p: Float) {
        if (!scrub || settling) return
        scrubP = p.coerceIn(0f, 1f)
        preview.renderScrub(scrubP)
        bar.setScrubT(scrubP)
        bar.setActive(indexOf(nearestState(scrubP)))
        badge.text = scrubLabel(scrubP)
    }

    override fun onScrubEnd(p: Float) {
        if (!scrub) return
        val target = nearestState(p)
        val targetP = SceneTimeline.anchor(target)
        if (kotlin.math.abs(targetP - p) < 0.002f) {
            scrub = false
            current = target
            finishScrub(targetP)
            return
        }
        settling = true
        settleFrom = p
        settleTo = targetP
        settleStartMs = System.nanoTime() / 1_000_000L
        if (!settleScheduled) {
            settleScheduled = true
            Choreographer.getInstance().postFrameCallback(settleCallback)
        }
        Lg.i(TAG, "scrub settle p=$p target=$target")
    }

    /** 收尾：落位到最近态；拖动结束与补间收尾共用。 */
    private fun finishScrub(targetP: Float) {
        val target = nearestState(targetP)
        scrub = false
        settling = false
        current = target
        rebuildDock()
        preview.render(target, null, 0f)
        bar.setScrub(false, 0f)
        bar.setActive(indexOf(target))
        badge.visibility = View.GONE
        hint.text = defaultHint
        Lg.i(TAG, "scrub end p=$targetP state=$target")
    }

    /** 整轨最近锚点：0.25 / 0.75 为分界，松手吸附。 */
    private fun nearestState(p: Float): SceneState = when {
        p < 0.25f -> SceneState.AOD
        p < 0.75f -> SceneState.LOCK
        else -> SceneState.HOME
    }

    /** 徽标文案：整轨位置 → 当前段 + 段内百分比。 */
    private fun scrubLabel(p: Float): String {
        val edge = SceneTimeline.edgeAt(p)
        val t = if (p >= 1f) 1f else SceneTimeline.localT(p)
        return edgeText(edge, t)
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
        val effects = EffectSettings.parse(readString(Prefs.KEY_EFFECT_CONFIG, ""))
        preview.lockWallpaperEffect = effects.lockWallpaperEffect
        preview.lockSpecialEffect = effects.lockSpecialEffect
        preview.lockWallpaperIntensity =
            effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.LOCK_WP_INTENSITY) / 100f
        preview.framePair = effects.aodFramePair
        preview.frameCropX = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_FRAME_X) / 100f
        preview.frameCropY = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_FRAME_Y) / 100f
        preview.frameCropSize = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_FRAME_SIZE) / 100f
        preview.frameCropAngle = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_FRAME_ANGLE)
        preview.allowAodDynamic = readBoolean(
            Prefs.KEY_EFFECT_ALLOW_AOD_DYNAMIC,
            Prefs.DEFAULT_EFFECT_ALLOW_AOD_DYNAMIC,
        ) || effects.aodAlwaysPlay
        preview.aodColorMix = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_COLOR_MIX)
        preview.aodRotateSpeed = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_ROTATE_SPEED)
        preview.aodPhotoTilt = effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.AOD_PHOTO_TILT)
        preview.homeTransition = effects.homeTransition
        preview.homeTransitionDurationMs =
            effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.HOME_TRANSITION_DURATION)
        preview.homeIconEffect = effects.homeIconEffect
        preview.homeIconDurationMs =
            effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.HOME_ICON_DURATION)
        preview.homeIconStaggerMs =
            effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.HOME_ICON_STAGGER)
        preview.homeIconTravelPercent =
            effects.param(com.coloros16.liquidglass.engine.effect.EffectParams.HOME_ICON_TRAVEL)
        loadFramePhoto(effects.aodFramePhoto)
        loadStylePhoto(effects.aodPhoto)
        header.text = context.getString(style.nameRes)
        bar.setActive(indexOf(current))
        rebuildDock()
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

    /** 预览手机框内联编辑：布局配置注入 + 手势回调 → 底部抽屉面板。 */
    private fun bindInlineEdit() {
        val delegate = preview.edit
        delegate.anchorOf = { state ->
            val a = sceneSpecAnchor(state)
            a.centerX to a.centerY
        }
        delegate.lockSettings = readLayout(com.coloros16.liquidglass.engine.edit.EditSurface.LOCK)
        delegate.aodSettings = readLayout(com.coloros16.liquidglass.engine.edit.EditSurface.AOD)
        delegate.interactive = true
        delegate.listener = object : AodPreviewEditDelegate.Listener {
            override fun onClockTapped() {
                editPanel?.showClockPanel(currentLayout() ?: return)
            }

            override fun onWidgetHold(typeId: String) {
                editPanel?.showWidgetMenu(typeId, currentLayout() ?: return)
            }

            override fun onWidgetTapped(typeId: String) {
                editPanel?.showWidgetMenu(typeId, currentLayout() ?: return)
            }

            override fun onPillTapped(typeId: String) {
                editPanel?.showPillPanel(typeId, currentLayout() ?: return)
            }

            override fun onPillSlotTapped(xFrac: Float, yFrac: Float) {
                editPanel?.showPillSlotPanel(xFrac, yFrac, currentLayout() ?: return)
            }

            override fun onLayoutEdited() {
                persistLayout()
            }

            override fun onSelectionChanged(selected: Boolean) {
                if (selected) hint.text = context.getString(R.string.settings_aod_edit_inline_hint)
                else hint.text = defaultHint
            }
        }
        editPanel = AodInlineEditPanel(
            context = context,
            onChanged = {
                persistLayout()
                preview.invalidate()
            },
        )
        Lg.i(TAG, "inline edit bound lock=${delegate.lockSettings != null} aod=${delegate.aodSettings != null}")
    }

    /**
     * 设置行「编辑」→ 就地编辑：关面板、把预览落到该态、选中时钟露出手柄。
     *
     * 正在播动画或拖动时不硬切，只选中当前态，避免打断转场。
     */
    private fun enterInlineEdit(editPageId: String) {
        settingsSheet?.dismiss()
        settingsSheet = null
        val target =
            if (editPageId == AodSettingsPages.PAGE_AOD_EDIT) SceneState.AOD else SceneState.LOCK
        val busy = playing || scrub || settling
        if (!busy && current != target) {
            current = target
            rebuildDock()
            preview.render(target, null, 0f)
            bar.setActive(indexOf(target))
        }
        preview.edit.selectClock()
        hint.text = context.getString(R.string.settings_aod_edit_inline_hint)
        Lg.i(TAG, "inline edit enter page=$editPageId target=$target busy=$busy state=$current")
    }

    private fun sceneSpecAnchor(state: SceneState): com.coloros16.liquidglass.engine.ClockAnchor =
        AodSceneCatalog.forStyle(draftId)?.clockAnchors?.get(state)
            ?: com.coloros16.liquidglass.engine.ClockAnchor()

    private fun currentLayout(): com.coloros16.liquidglass.engine.edit.LockLayoutSettings? =
        if (current == SceneState.AOD) preview.edit.aodSettings else preview.edit.lockSettings

    private fun currentSurface(): com.coloros16.liquidglass.engine.edit.EditSurface =
        if (current == SceneState.AOD) com.coloros16.liquidglass.engine.edit.EditSurface.AOD
        else com.coloros16.liquidglass.engine.edit.EditSurface.LOCK

    private fun persistLayout() {
        val layout = currentLayout() ?: return
        writeLayout(currentSurface(), layout)
    }

    /** 3D 场景壁纸判定：动态壁纸服务或场景族息屏样式即视为 3D 场景；按需查询 + TTL 缓存。 */
    private fun scene3dNow(): Boolean {
        if (com.coloros16.liquidglass.engine.effect.StyleFamilyMap.familyOf(draftId) ==
            com.coloros16.liquidglass.engine.effect.EffectFamily.SCENE_3D
        ) return true
        val now = android.os.SystemClock.uptimeMillis()
        if (now - scene3dCheckedAt < 1000L) return scene3dActive
        scene3dCheckedAt = now
        scene3dActive = try {
            WallpaperManager.getInstance(context).wallpaperInfo != null
        } catch (t: Throwable) {
            false
        }
        return scene3dActive
    }

    /** 按当前三态重建圆钮：息屏与锁屏 4（效果/壁纸/样式/其他）、桌面 3（壁纸/样式/其他）。 */
    private fun rebuildDock() {
        if (!::dockBar.isInitialized || dockState == current) return
        dockState = current
        dockBar.removeAllViews()
        val pages = AodSettingsPages.categoriesOf(current)
        for (pageId in pages) {
            val label = context.getString(AodSettingsPages.categoryLabel(pageId))
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(R.drawable.bg_aod_dock_press)
                isClickable = true
                isFocusable = true
                setOnClickListener { openCategory(pageId) }
            }
            item.addView(
                android.widget.ImageView(context).apply {
                    setBackgroundResource(R.drawable.bg_aod_dock)
                    setImageResource(AodSettingsPages.categoryIcon(pageId))
                    setColorFilter(0xF2FFFFFF.toInt())
                    setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
                    contentDescription = label
                },
                LinearLayout.LayoutParams(dpi(40f), dpi(40f)),
            )
            item.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(0xB8FFFFFF.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dpi(5f), 0, 0)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            dockBar.addView(item)
        }
        Lg.i(TAG, "dock rebuilt state=$current buttons=${pages.size}")
    }

    /** 圆钮 → 页内面板：设置分类复用声明式清单，样式用模板抽屉，全部不离开本页。 */
    private fun openCategory(pageId: String) {
        settingsSheet?.dismiss()
        settingsSheet = null
        if (pageId == AodSettingsPages.PAGE_AOD_STYLE || pageId == AodSettingsPages.PAGE_LOCK_STYLE) {
            showStyleSheet()
            return
        }
        val page = AodSettingsPages.of(context, pageId, styleId = draftId) ?: return
        val (dialog, content) = buildSheet(
            context.getString(AodSettingsPages.categoryLabel(pageId)),
            stateName(current),
        )
        val panel = ScrollView(context).apply {
            addView(
                LinearLayout(context).apply {
                    id = R.id.aodSettingsContainer
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpi(16f), 0, dpi(16f), dpi(8f))
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        content.addView(
            panel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val controller = AodSettingsController(
            context = context,
            root = panel,
            page = page,
            readSettings = readSettings,
            writeSettings = writeSettings,
            readBool = readBoolean,
            writeBool = writeBoolean,
            push = { screenId ->
                val opener = openScreen
                if (opener == null) {
                    Lg.w(TAG, "panel nav no opener screen=$screenId")
                } else {
                    settingsSheet?.dismiss()
                    settingsSheet = null
                    Lg.i(TAG, "panel nav open screen=$screenId")
                    opener(screenId)
                }
            },
            scene3dActive = { scene3dNow() },
            onChanged = { updateUi() },
            styleOverride = draftId,
            editAction = { action ->
                when {
                    action == "widget_picker" -> currentLayout()?.let { editPanel?.showWidgetPicker(it) }
                    action.startsWith("inline_edit:") ->
                        enterInlineEdit(action.removePrefix("inline_edit:"))
                    else -> Lg.w(TAG, "edit action unknown=$action")
                }
            },
            pickPhoto = pickPhoto,
        )
        controller.bind()
        settingsSheet = dialog
        dialog.setOnDismissListener { settingsSheet = null }
        dialog.show()
        Lg.i(TAG, "panel open page=$pageId state=$current style=$draftId")
    }

    private var stylePhotoUri: String? = null

    private fun loadStylePhoto(uri: String) {
        if (uri == stylePhotoUri) return
        stylePhotoUri = uri
        if (uri.isBlank()) {
            preview.stylePhoto = null
            return
        }
        Thread {
            val bitmap = try {
                val parsed = android.net.Uri.parse(uri)
                context.contentResolver.openInputStream(parsed)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            } catch (t: Throwable) {
                Lg.w(TAG, "style photo load failed ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (bitmap != null) {
                preview.post { preview.stylePhoto = bitmap }
            }
        }.apply { name = "aod-style-photo"; isDaemon = true }.start()
    }

    private var framePhotoUri: String? = null

    private fun loadFramePhoto(uri: String) {
        if (uri == framePhotoUri) return
        framePhotoUri = uri
        if (uri.isBlank()) {
            preview.framePhoto = null
            return
        }
        Thread {
            val bitmap = try {
                if (uri == "sakura") {
                    context.assets.open("aod/examples/sakura-color.jpg").use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                } else {
                    val parsed = android.net.Uri.parse(uri)
                    context.contentResolver.openInputStream(parsed)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (t: Throwable) {
                Lg.w(TAG, "frame photo load failed ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (bitmap != null) {
                preview.post { preview.framePhoto = bitmap }
            }
        }.apply { name = "aod-frame-photo"; isDaemon = true }.start()
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
