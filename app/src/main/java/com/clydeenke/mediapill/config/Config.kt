package com.clydeenke.mediapill.config

/**
 * 模块配置定义（app 端与 hook 端共享）。
 *
 * key 存 RemotePreferences（跨进程同步到 hook 端 SystemUI）。
 */
object Config {
    const val GROUP = "mediapill_config"

    /** 模块总开关。关时 hook 完全透传，锁屏媒体控件行为原生（零干预）。 */
    const val KEY_MASTER_SWITCH = "master_switch"
    const val MASTER_SWITCH_DEFAULT = false

    /** 药丸纵向位置（百分比，78–90 可调）。 */
    const val KEY_PILL_POSITION_PERCENT = "pill_position_percent"
    const val PILL_POSITION_PERCENT_DEFAULT = 88

    // 新增用户可调参数
    /** Y 轴偏移（dp，可正可负，默认 0）。 */
    const val KEY_PILL_Y_OFFSET_DP = "pill_y_offset_dp"
    const val PILL_Y_OFFSET_DP_DEFAULT = 0

    /** 药丸最大宽度（dp，默认 280）。 */
    const val KEY_PILL_MAX_WIDTH_DP = "pill_max_width_dp"
    const val PILL_MAX_WIDTH_DP_DEFAULT = 280

    /** 药丸最小宽度（dp，默认 180）。 */
    const val KEY_PILL_MIN_WIDTH_DP = "pill_min_width_dp"
    const val PILL_MIN_WIDTH_DP_DEFAULT = 180

    /** 背景透明度（%，默认 85）。 */
    const val KEY_PILL_ALPHA_PERCENT = "pill_alpha_percent"
    const val PILL_ALPHA_PERCENT_DEFAULT = 85
}
