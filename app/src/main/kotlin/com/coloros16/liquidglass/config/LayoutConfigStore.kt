package com.coloros16.liquidglass.config

import android.content.Context
import android.content.SharedPreferences
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface

/** 锁屏 / 息屏编辑配置读写入口。 */
object LayoutConfigStore {

    const val TAG = "LgLayout"

    fun readLock(context: Context): LockLayoutSettings = readLock(Prefs.read(context))

    fun readLock(api: XposedInterface): LockLayoutSettings = readLock(Prefs.read(api))

    fun readLock(prefs: SharedPreferences): LockLayoutSettings =
        read(prefs, Prefs.KEY_LOCK_LAYOUT_CONFIG, EditSurface.LOCK)

    fun readAod(context: Context): LockLayoutSettings = readAod(Prefs.read(context))

    fun readAod(api: XposedInterface): LockLayoutSettings = readAod(Prefs.read(api))

    fun readAod(prefs: SharedPreferences): LockLayoutSettings =
        read(prefs, Prefs.KEY_AOD_LAYOUT_CONFIG, EditSurface.AOD)

    fun writeLock(context: Context, settings: LockLayoutSettings) = writeLock(Prefs.read(context), settings)

    fun writeLock(api: XposedInterface, settings: LockLayoutSettings) = writeLock(Prefs.read(api), settings)

    fun writeLock(prefs: SharedPreferences, settings: LockLayoutSettings) =
        write(prefs, Prefs.KEY_LOCK_LAYOUT_CONFIG, settings)

    fun writeAod(context: Context, settings: LockLayoutSettings) = writeAod(Prefs.read(context), settings)

    fun writeAod(api: XposedInterface, settings: LockLayoutSettings) = writeAod(Prefs.read(api), settings)

    fun writeAod(prefs: SharedPreferences, settings: LockLayoutSettings) =
        write(prefs, Prefs.KEY_AOD_LAYOUT_CONFIG, settings)

    private fun read(prefs: SharedPreferences, key: String, surface: EditSurface): LockLayoutSettings =
        try {
            LockLayoutSettings.parse(prefs.getString(key, Prefs.DEFAULT_LAYOUT_CONFIG), surface)
        } catch (t: Throwable) {
            Lg.w(TAG, "layout config parse failed: ${t.javaClass.simpleName}: ${t.message}")
            LockLayoutSettings.defaults(surface)
        }

    private fun write(prefs: SharedPreferences, key: String, settings: LockLayoutSettings) {
        try {
            prefs.edit().putString(key, settings.toText()).apply()
        } catch (t: Throwable) {
            Lg.e(TAG, "layout config write failed", t)
        }
    }
}
