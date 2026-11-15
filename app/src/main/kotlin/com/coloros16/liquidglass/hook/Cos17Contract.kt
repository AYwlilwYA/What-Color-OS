package com.coloros16.liquidglass.hook

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * C17 结构契约：类名/方法名/字段名常量表 + 字段类型校验。
 *
 * 探测只做结构检查，不读 Application/PackageManager，装载早期即可用；
 * 版本差异一律走 VersionPool，本对象不写版本判断。
 */
object Cos17Contract {

    /** 17.3.12 桌面 `com.oplus.posteffect.drawable.b` 真 DEX 字段契约（jadx 伪名一律不用）。 */
    object Launcher17 {
        const val CLASS_INLINE_DRAWABLE = "com.oplus.posteffect.drawable.b"
        const val CLASS_CORNER_TYPE = "com.oplus.posteffect.params.CornerType"

        const val FIELD_BLUR_SHADER_PAINT = "M"
        const val FIELD_PAINT = "f"
        const val FIELD_RENDER_NODE = "h"
        const val FIELD_DRAWABLE_PATH = "i"
        const val FIELD_PAINT_ALPHA = "F"
        const val FIELD_ENABLE_BLUR_SHADER = "a"
        const val FIELD_CORNER_TYPE_CONIC = "c"

        /** 字段名 → 期望类型（[checkedField] 校验用，防单字母撞名）。 */
        val FIELD_TYPES: Map<String, Class<*>> = mapOf(
            FIELD_BLUR_SHADER_PAINT to Paint::class.java,
            FIELD_PAINT to Paint::class.java,
            FIELD_RENDER_NODE to RenderNode::class.java,
            FIELD_DRAWABLE_PATH to Path::class.java,
            FIELD_PAINT_ALPHA to Integer.TYPE,
            FIELD_ENABLE_BLUR_SHADER to Boolean::class.javaPrimitiveType!!,
        )
    }

    /**
     * 取字段并校验类型：沿父链找同名字段，类型不符按缺失处理（单字母字段撞名防护）。
     *
     * @throws NoSuchFieldException 找不到或类型不符
     */
    fun checkedField(owner: Class<*>, name: String, type: Class<*>): Field {
        var cursor: Class<*>? = owner
        while (cursor != null) {
            try {
                val field = cursor.getDeclaredField(name)
                if (field.type != type) {
                    throw NoSuchFieldException("${owner.name}.$name 类型不符：${field.type.name} != ${type.name}")
                }
                return field.apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                if (e.message?.contains("类型不符") == true) throw e
            }
            cursor = cursor.superclass
        }
        throw NoSuchFieldException("${owner.name}.$name")
    }

    /** 桌面 17.3.12 内联录制结构指纹（字段 + 静态方法齐了才算命中）。 */
    fun launcherInline(cl: ClassLoader?): Boolean = try {
        if (cl == null) false else {
            val base = Class.forName(Launcher17.CLASS_INLINE_DRAWABLE, false, cl)
            if (!Drawable::class.java.isAssignableFrom(base)) false else {
                checkedField(base, Launcher17.FIELD_BLUR_SHADER_PAINT, Paint::class.java)
                checkedField(base, Launcher17.FIELD_RENDER_NODE, RenderNode::class.java)
                checkedField(base, Launcher17.FIELD_DRAWABLE_PATH, Path::class.java)
                checkedField(base, Launcher17.FIELD_ENABLE_BLUR_SHADER, Boolean::class.javaPrimitiveType!!)
                base.declaredMethods.any {
                    it.name == "a" && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                        it.parameterTypes.size == 1 && base.isAssignableFrom(it.parameterTypes[0])
                }
            }
        }
    } catch (_: Throwable) {
        false
    }

    /** 倾斜原生化结构指纹（ZuyqaWindowTilt + ZuyqaAnimationTuning）。 */
    fun nativeTilt(cl: ClassLoader): Boolean = try {
        Class.forName("com.android.quickstep.util.animation.ZuyqaWindowTilt", false, cl)
            .getMethod("isEnabled")
        Class.forName("com.android.common.util.ZuyqaAnimationTuning", false, cl).getMethod(
            "getInt", android.content.Context::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        true
    } catch (_: Throwable) {
        false
    }
}
