package com.clydeenke.mediapill.config

/**
 * 模块配置定义（app 端与 hook 端共享）。
 *
 * key 存 RemotePreferences（跨进程同步到 hook 端 SystemUI）。
 */
object Config {
    const val GROUP = "mediapill_config"

    /** 模块总开关。关时 hook 完全透传，锁屏媒体控件行为原生（零干预）。 */
    const val MASTER_SWITCH = "master_switch"
    const val MASTER_SWITCH_DEFAULT = false

    /** 药丸纵向位置（百分比，78–90 可调）。 */
    const val PILL_POSITION_PERCENT = "pill_position_percent"
    const val PILL_POSITION_PERCENT_DEFAULT = 90
}
