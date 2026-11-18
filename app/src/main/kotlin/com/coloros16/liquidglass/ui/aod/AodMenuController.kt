package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg

/** 息屏与锁屏二级菜单：接管开关 + 显示与配置 + 息屏/锁屏/桌面分组入口。 */
class AodMenuController(
    private val context: Context,
    private val root: View,
    private val readBoolean: (String, Boolean) -> Boolean,
    private val writeBoolean: (String, Boolean) -> Unit,
    private val openDisplay: () -> Unit,
    private val openConfigs: () -> Unit,
    private val openPage: (String) -> Unit,
) {

    private companion object {
        const val TAG = "LgAodUi"
    }

    private var bound = false

    fun bind() {
        if (bound) {
            refresh()
            return
        }
        bindSwitch(
            R.id.aodSwitchWallpaper,
            Prefs.KEY_ALIVE_AOD_WALLPAPER,
            Prefs.DEFAULT_ALIVE_AOD_WALLPAPER,
        )
        bindSwitch(
            R.id.aodSwitchScreen,
            Prefs.KEY_ALIVE_AOD_SCREEN,
            Prefs.DEFAULT_ALIVE_AOD_SCREEN,
        )
        bindSwitch(
            R.id.aodSwitchLockShow,
            Prefs.KEY_ALIVE_AOD_LOCK_SHOW,
            Prefs.DEFAULT_ALIVE_AOD_LOCK_SHOW,
        )
        bindSwitch(
            R.id.aodSwitchDynamic,
            Prefs.KEY_EFFECT_ALLOW_AOD_DYNAMIC,
            Prefs.DEFAULT_EFFECT_ALLOW_AOD_DYNAMIC,
        )

        bindRow(
            R.id.aodRowDisplay,
            R.string.settings_aod_display_row,
            R.string.settings_aod_display_row_desc,
            R.drawable.ic_row_config,
        ) { openDisplay() }
        bindRow(
            R.id.aodRowConfigs,
            R.string.settings_aod_configs_row,
            R.string.settings_aod_configs_row_desc,
            R.drawable.ic_row_palette,
        ) { openConfigs() }

        bindPageRow(
            R.id.aodRowOtherAll,
            R.string.settings_aod_hub_other_row,
            R.string.settings_aod_hub_other_row_desc,
            R.drawable.ic_row_list,
            AodSettingsPages.PAGE_OTHER_ALL,
        )
        bindPageRow(
            R.id.aodRowAdvanced,
            R.string.settings_aod_advanced_row,
            R.string.settings_aod_advanced_row_desc,
            R.drawable.ic_row_generic,
            AodSettingsPages.PAGE_ADVANCED,
        )

        bound = true
        Lg.i(TAG, "aod menu bound")
    }

    fun refresh() {
        if (!bound) return
        refreshSwitch(R.id.aodSwitchWallpaper, Prefs.KEY_ALIVE_AOD_WALLPAPER, Prefs.DEFAULT_ALIVE_AOD_WALLPAPER)
        refreshSwitch(R.id.aodSwitchScreen, Prefs.KEY_ALIVE_AOD_SCREEN, Prefs.DEFAULT_ALIVE_AOD_SCREEN)
        refreshSwitch(R.id.aodSwitchLockShow, Prefs.KEY_ALIVE_AOD_LOCK_SHOW, Prefs.DEFAULT_ALIVE_AOD_LOCK_SHOW)
        refreshSwitch(
            R.id.aodSwitchDynamic,
            Prefs.KEY_EFFECT_ALLOW_AOD_DYNAMIC,
            Prefs.DEFAULT_EFFECT_ALLOW_AOD_DYNAMIC,
        )
    }

    private fun bindSwitch(id: Int, key: String, default: Boolean) {
        val toggle = root.findViewById<Switch>(id) ?: return missing(id)
        toggle.setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_thumb))
        toggle.setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_track))
        refreshSwitch(id, key, default)
    }

    private fun refreshSwitch(id: Int, key: String, default: Boolean) {
        val toggle = root.findViewById<Switch>(id) ?: return
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = readBoolean(key, default)
        toggle.setOnCheckedChangeListener { _, checked ->
            writeBoolean(key, checked)
            Lg.i(TAG, "aod menu $key=$checked")
        }
    }

    private fun bindPageRow(id: Int, title: Int, desc: Int, icon: Int, pageId: String) {
        bindRow(id, title, desc, icon) { openPage(pageId) }
    }

    private fun bindRow(id: Int, title: Int, desc: Int, icon: Int, onClick: () -> Unit) {
        val row = root.findViewById<View>(id) ?: return missing(id)
        row.findViewById<TextView>(R.id.rowTitle)?.text = context.getString(title)
        row.findViewById<TextView>(R.id.rowDesc)?.apply {
            text = context.getString(desc)
            visibility = View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.rowIcon)?.apply {
            setImageResource(icon)
            setColorFilter(context.getColor(R.color.lg_blued_ink))
        }
        row.setOnClickListener { onClick() }
    }

    private fun missing(id: Int) {
        Lg.w(TAG, "aod menu missing view 0x${Integer.toHexString(id)}")
    }
}
