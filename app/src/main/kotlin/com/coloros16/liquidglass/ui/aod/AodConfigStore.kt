package com.coloros16.liquidglass.ui.aod

import android.content.SharedPreferences
import com.coloros16.liquidglass.config.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一份保存过的息屏配置：名称 + 基于哪个默认样式。 */
data class AodConfig(
    val id: String,
    val name: String,
    val styleId: String,
)

/** 配置列表的本地存储（只进模块 XML，不写框架侧）。 */
class AodConfigStore(private val prefs: SharedPreferences) {

    fun list(): List<AodConfig> {
        val raw = prefs.getString(Prefs.KEY_ALIVE_AOD_CONFIGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val styleId = item.optString("style").takeIf { AodStyleCatalog.find(it).id == it } ?: continue
                    add(AodConfig(id, name, styleId))
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun add(styleId: String, name: String): AodConfig {
        val config = AodConfig(UUID.randomUUID().toString().substring(0, 8), name, styleId)
        save(list() + config)
        return config
    }

    fun rename(id: String, name: String) {
        save(list().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun find(id: String?): AodConfig? = id?.let { target -> list().firstOrNull { it.id == target } }

    private fun save(configs: List<AodConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("style", config.styleId),
            )
        }
        prefs.edit().putString(Prefs.KEY_ALIVE_AOD_CONFIGS, array.toString()).apply()
    }
}
