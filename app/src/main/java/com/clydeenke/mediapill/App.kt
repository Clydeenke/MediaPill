package com.clydeenke.mediapill

import android.app.Application
import android.util.Log
import com.clydeenke.mediapill.config.ConfigService
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * App 端 Application。
 *
 * 注册 XposedServiceHelper，bind 成功后通过 [ConfigService] 暴露 RemotePreferences，
 * 供配置 UI 读写。service 未 bind 时 UI 回退默认值并提示。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "XposedService bound, api=${service.apiVersion}")
                ConfigService.bind(service)
            }

            override fun onServiceDied(service: XposedService) {
                ConfigService.unbind()
            }
        })
    }

    companion object {
        private const val TAG = "MediaPill"
    }
}
