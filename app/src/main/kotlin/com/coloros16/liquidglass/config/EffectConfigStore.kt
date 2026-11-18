package com.coloros16.liquidglass.config

import android.content.Context
import android.content.SharedPreferences
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface

/**
 * 效果体系配置读写入口。
 *
 * 两处存储：`effect_allow_aod_dynamic`（独立布尔，hook 侧低成本读）与 `effect_config`（版本化文本）。
 * 读取时布尔键优先，保证「其他」页的总闸与文本配置不一致时以总闸为准。
 */
object EffectConfigStore {

    const val TAG = "LgEffect"

    fun read(context: Context): EffectSettings = read(Prefs.read(context))

    fun read(api: XposedInterface): EffectSettings = read(Prefs.read(api))

    fun read(prefs: SharedPreferences): EffectSettings {
        val settings = try {
            EffectSettings.parse(prefs.getString(Prefs.KEY_EFFECT_CONFIG, Prefs.DEFAULT_EFFECT_CONFIG))
        } catch (t: Throwable) {
            Lg.w(TAG, "effect config parse failed: ${t.javaClass.simpleName}: ${t.message}")
            EffectSettings.defaults()
        }
        settings.allowAodDynamic = try {
            prefs.getBoolean(Prefs.KEY_EFFECT_ALLOW_AOD_DYNAMIC, Prefs.DEFAULT_EFFECT_ALLOW_AOD_DYNAMIC)
        } catch (t: Throwable) {
            settings.allowAodDynamic
        }
        return settings
    }

    fun write(context: Context, settings: EffectSettings) = write(Prefs.read(context), settings)

    fun write(api: XposedInterface, settings: EffectSettings) = write(Prefs.read(api), settings)

    fun write(prefs: SharedPreferences, settings: EffectSettings) {
        try {
            prefs.edit()
                .putString(Prefs.KEY_EFFECT_CONFIG, settings.toText())
                .putBoolean(Prefs.KEY_EFFECT_ALLOW_AOD_DYNAMIC, settings.allowAodDynamic)
                .apply()
        } catch (t: Throwable) {
            Lg.e(TAG, "effect config write failed", t)
        }
    }
}
