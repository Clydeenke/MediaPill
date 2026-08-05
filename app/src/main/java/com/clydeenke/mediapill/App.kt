package com.clydeenke.mediapill

import android.app.Application
import com.clydeenke.mediapill.config.ConfigService

/**
 * App 端 Application。
 *
 * 初始化本地 SharedPreferences（由 RemotePrefProvider 暴露给 hook 端）。
 * 不依赖 libxposed-service，兼容所有 Xposed 框架。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigService.init(this)
    }
}
