package com.clydeenke.mediapill.config

import android.content.Context
import android.content.SharedPreferences

/**
 * App 端配置访问。
 *
 * 直接使用本地 SharedPreferences（由 [RemotePrefProvider] 暴露给 hook 端）。
 * 不依赖 libxposed-service，兼容所有 Xposed 框架。
 */
object ConfigService {

    @Volatile
    private var prefs: SharedPreferences? = null

    /** App 端在 Application.onCreate 调用，初始化本地 SharedPreferences。 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE)
    }

    val isReady: Boolean
        get() = prefs != null

    fun get(): SharedPreferences? = prefs
}
