package com.clydeenke.mediapill.config

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService

/**
 * app 端 XposedService 绑定与 RemotePreferences 访问。
 *
 * [com.clydeenke.mediapill.App] 在 onCreate 注册 XposedServiceHelper.registerListener，
 * service bind 成功后调 [bind]，UI 即可通过 [get] 拿到 RemotePreferences 读写配置。
 *
 * 若 service 未 bind（LSPosed 未启用 / 模块未启用），[get] 返回 null，
 * UI 应回退到默认值并提示用户。[onReady] 让 UI 在 bind 成功后自动接上。
 */
object ConfigService {

    private const val TAG = "ConfigService"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val listeners = mutableListOf<(SharedPreferences) -> Unit>()

    val isReady: Boolean
        get() = prefs != null

    fun bind(service: XposedService) {
        try {
            prefs = service.getRemotePreferences(Config.GROUP)
            Log.i(TAG, "remote prefs bound: ${prefs?.all}")
            val p = prefs ?: return
            synchronized(listeners) { listeners.toList() }.forEach { it(p) }
            synchronized(listeners) { listeners.clear() }
        } catch (t: Throwable) {
            Log.e(TAG, "bind remote prefs failed", t)
        }
    }

    fun unbind() {
        prefs = null
        Log.w(TAG, "service died, prefs unbound")
    }

    fun get(): SharedPreferences? = prefs

    /** service 已 bind 时立即回调，否则暂存待 bind 后回调（一次性）。 */
    fun onReady(cb: (SharedPreferences) -> Unit) {
        val p = prefs
        if (p != null) {
            cb(p)
        } else {
            synchronized(listeners) { listeners.add(cb) }
        }
    }
}
