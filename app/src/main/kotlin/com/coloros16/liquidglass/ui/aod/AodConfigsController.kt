package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 配置页：列出保存过的配置，支持新建 / 使用 / 重命名 / 删除。 */
class AodConfigsController(
    private val context: Context,
    private val root: View,
    private val localPrefs: SharedPreferences,
    private val readString: (String, String) -> String,
    private val writeString: (String, String) -> Unit,
    private val writeBoolean: (String, Boolean) -> Unit,
) {

    private companion object {
        const val TAG = "LgAodUi"
    }

    private val density = context.resources.displayMetrics.density
    private val ink100 = context.getColor(R.color.lg_ink_100)
    private val ink400 = context.getColor(R.color.lg_ink_400)
    private val bluedInk = context.getColor(R.color.lg_blued_ink)
    private val danger = context.getColor(R.color.lg_ruby)

    private val store = AodConfigStore(localPrefs)

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    fun bind() {
        list = root.findViewById(R.id.aodConfigList) ?: return missing("aodConfigList")
        empty = root.findViewById(R.id.aodConfigEmpty) ?: return missing("aodConfigEmpty")
        root.findViewById<View>(R.id.aodConfigAdd)?.setOnClickListener { createConfig() }
        render()
    }

    private fun render() {
        list.removeAllViews()
        val configs = store.list()
        val appliedId = localPrefs.getString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, null)
        empty.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        configs.forEach { config ->
            list.addView(buildRow(config, config.id == appliedId))
        }
        Lg.i(TAG, "configs render count=${configs.size} applied=$appliedId")
    }

    private fun buildRow(config: AodConfig, current: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(15f), dpi(12f), dpi(12f), dpi(12f))
            isClickable = true
            isFocusable = true
            setOnClickListener { useConfig(config) }
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val styleName = context.getString(AodStyleCatalog.find(config.styleId).nameRes)
        texts.addView(
            TextView(context).apply {
                text = config.name
                textSize = 15f
                setTextColor(ink100)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        texts.addView(
            TextView(context).apply {
                text = context.getString(R.string.settings_aod_config_based_on, styleName) +
                    if (current) " · " + context.getString(R.string.settings_aod_config_current) else ""
                textSize = 11.5f
                setTextColor(if (current) bluedInk else ink400)
                setPadding(0, dpi(3f), 0, 0)
            },
        )
        row.addView(texts)

        row.addView(
            actionText(context.getString(R.string.settings_aod_config_rename)) { renameConfig(config) },
        )
        row.addView(
            actionText(context.getString(R.string.settings_aod_config_delete)) { deleteConfig(config) },
        )
        return row
    }

    private fun actionText(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 12.5f
        setTextColor(if (label == context.getString(R.string.settings_aod_config_delete)) danger else bluedInk)
        gravity = Gravity.CENTER
        setPadding(dpi(10f), dpi(8f), dpi(2f), dpi(8f))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun createConfig() {
        val configs = store.list()
        val name = nextName(configs)
        val styleId = readString(Prefs.KEY_ALIVE_AOD_STYLE_ID, Prefs.DEFAULT_ALIVE_AOD_STYLE_ID)
        store.add(styleId, name)
        render()
        toast(context.getString(R.string.settings_aod_config_created))
        Lg.i(TAG, "config create name=$name style=$styleId")
    }

    private fun nextName(configs: List<AodConfig>): String {
        var index = configs.size + 1
        while (configs.any { it.name == context.getString(R.string.settings_aod_config_new_name, index) }) {
            index++
        }
        return context.getString(R.string.settings_aod_config_new_name, index)
    }

    private fun useConfig(config: AodConfig) {
        writeString(Prefs.KEY_ALIVE_AOD_STYLE_ID, config.styleId)
        writeBoolean(Prefs.KEY_ALIVE_AOD_STYLE, true)
        localPrefs.edit().putString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, config.id).apply()
        render()
        toast(context.getString(R.string.settings_aod_applied))
        Lg.i(TAG, "config use id=${config.id} style=${config.styleId}")
    }

    private fun renameConfig(config: AodConfig) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(config.name)
            setSelection(config.name.length)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_aod_config_rename_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(context.getString(R.string.settings_aod_config_name_empty))
                    return@setPositiveButton
                }
                store.rename(config.id, name)
                render()
                toast(context.getString(R.string.settings_aod_config_renamed))
                Lg.i(TAG, "config rename id=${config.id} name=$name")
            }
            .show()
    }

    private fun deleteConfig(config: AodConfig) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_aod_config_delete_title)
            .setMessage(context.getString(R.string.settings_aod_config_delete_message, config.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_aod_config_delete) { _, _ ->
                store.remove(config.id)
                if (localPrefs.getString(Prefs.KEY_ALIVE_AOD_CONFIG_ID, null) == config.id) {
                    localPrefs.edit().remove(Prefs.KEY_ALIVE_AOD_CONFIG_ID).apply()
                }
                render()
                toast(context.getString(R.string.settings_aod_config_deleted))
                Lg.i(TAG, "config delete id=${config.id}")
            }
            .show()
    }

    private fun missing(id: String) {
        Lg.w(TAG, "configs bind skipped: missing view $id")
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun dpi(value: Float): Int = (value * density).toInt()
}
