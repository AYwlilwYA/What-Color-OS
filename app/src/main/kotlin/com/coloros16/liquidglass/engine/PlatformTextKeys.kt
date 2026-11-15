package com.coloros16.liquidglass.engine

/** 平台件对外文案键：键名与 `strings.xml` 资源名一一对应，UI 按键取文案。 */
object PlatformTextKeys {

    const val DEGRADE_LAYER_UNSUPPORTED = "platform_degrade_layer_unsupported"
    const val DEGRADE_VERSION_MISMATCH = "platform_degrade_version_mismatch"
    const val DEGRADE_PROBE_FAILED = "platform_degrade_probe_failed"
    const val DEGRADE_NOT_REGISTERED = "platform_degrade_not_registered"

    const val FUSE_TRIPPED = "platform_fuse_tripped"
    const val FUSE_RESTORED = "platform_fuse_restored"

    val all: List<String> = listOf(
        DEGRADE_LAYER_UNSUPPORTED,
        DEGRADE_VERSION_MISMATCH,
        DEGRADE_PROBE_FAILED,
        DEGRADE_NOT_REGISTERED,
        FUSE_TRIPPED,
        FUSE_RESTORED,
    )
}
