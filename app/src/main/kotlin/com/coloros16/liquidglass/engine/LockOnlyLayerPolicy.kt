package com.coloros16.liquidglass.engine

import com.coloros16.liquidglass.engine.edit.EditSurface

/**
 * 附加层的面归属：小组件与大丸只属锁屏面，息屏面没有这两样。
 *
 * 纯求值；挂层与撕除由调用方做。
 */
object LockOnlyLayerPolicy {

    /** 该附加层是否只属锁屏面。 */
    fun lockOnly(kind: Kind): Boolean = when (kind) {
        Kind.WIDGETS, Kind.PILLS -> true
        Kind.CLOCK, Kind.CONTENT -> false
    }

    /** 附加层种类。 */
    enum class Kind { WIDGETS, PILLS, CLOCK, CONTENT }

    /**
     * 该附加层此刻是否该上岗。
     *
     * 只属锁屏的层：无论当前在哪一面，都只看锁屏面闸；
     * 因为它的宿主是锁屏根容器，息屏面泵到它时也不该借机挂上。
     */
    fun shouldMount(kind: Kind, current: EditSurface, lockGate: Boolean, aodGate: Boolean): Boolean =
        if (lockOnly(kind)) lockGate else when (current) {
            EditSurface.AOD -> aodGate
            EditSurface.LOCK -> lockGate
        }

    /** 关闸时是否该撕除已挂的层。 */
    fun shouldRelease(kind: Kind, mounted: Boolean, lockGate: Boolean, aodGate: Boolean, current: EditSurface): Boolean =
        mounted && !shouldMount(kind, current, lockGate, aodGate)
}