package com.clydeenke.mediapill.xposed

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPill Xposed 入口（libxposed API 102）。
 *
 * Stage 1：隐藏原生锁屏媒体控件。
 *
 * Hook 策略（双保险）：
 * 1. KeyguardMediaController.setVisibility() → 执行后强制 container GONE + visible=false
 * 2. MediaHost.updateViewVisibility()         → 锁屏 host 执行后强制 hostView GONE
 *
 * 当 master_switch 关闭时，所有 hook 透传，零干预。
 */
class PillHookEntry : XposedModule() {

    companion object {
        private const val TAG = "MediaPill"
        private const val PKG_SYSTEMUI = "com.android.systemui"
        private val registered = AtomicBoolean(false)

        // LOS 23 探测确认的类路径
        private const val CLS_KEYGUARD_MEDIA_CTRL =
            "com.android.systemui.media.controls.ui.controller.KeyguardMediaController"
        private const val CLS_MEDIA_HOST =
            "com.android.systemui.media.controls.ui.view.MediaHost"

        // MediaHost.location: 0 = 锁屏 (AOSP HOST_LOCATION_LOCKSCREEN)
        private const val HOST_LOCATION_LOCKSCREEN = 0

        @Volatile
        private var systemUiContext: Context? = null
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded: process=${param.processName} " +
                "framework=$frameworkName($frameworkVersionCode) API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "onPackageReady: pkg=${param.packageName} first=${param.isFirstPackage}")
        if (param.packageName != PKG_SYSTEMUI) return
        if (!registered.compareAndSet(false, true)) return
        Log.i(TAG, "===== MediaPill Stage 1: registering hooks =====")
        registerStage1Hooks(param.classLoader)
    }

    // ──────────────────────────────────────────────────
    //  Stage 1: 隐藏原生锁屏媒体控件
    // ──────────────────────────────────────────────────

    private fun registerStage1Hooks(cl: ClassLoader) {
        try {
            hookKeyguardMediaController(cl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook KeyguardMediaController", e)
        }
        try {
            hookMediaHostVisibility(cl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook MediaHost", e)
        }
    }

    /**
     * Hook KeyguardMediaController:
     * - 构造函数: 捕获 Context（用于读取配置）
     * - setVisibility(int, ViewGroup): 主开关开启时，执行后强制 GONE
     * - onMediaHostVisibilityChanged(boolean): 日志监控
     */
    private fun hookKeyguardMediaController(cl: ClassLoader) {
        val clazz = Class.forName(CLS_KEYGUARD_MEDIA_CTRL, false, cl)

        // 1. 构造函数 → 捕获 Context
        val constructor = clazz.declaredConstructors[0]
        hook(constructor).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                chain.proceed()
                if (systemUiContext == null) {
                    try {
                        val ctx = clazz.getDeclaredField("context")
                            .apply { isAccessible = true }
                            .get(chain.thisObject) as? Context
                        if (ctx != null) {
                            systemUiContext = ctx
                            Log.i(TAG, "Context captured from KeyguardMediaController")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to capture context", e)
                    }
                }
                return null
            }
        })

        // 2. setVisibility(int, ViewGroup) → 主开关开启时强制隐藏
        val setVisMethod = clazz.getDeclaredMethod(
            "setVisibility",
            Int::class.javaPrimitiveType,
            ViewGroup::class.java
        )
        hook(setVisMethod).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                chain.proceed() // 先让原始逻辑执行
                if (!isMasterEnabled()) return null

                try {
                    val thisObj = chain.thisObject
                    val container = chain.args[1] as? ViewGroup

                    // 强制隐藏容器
                    container?.visibility = View.GONE
                    // 同步 visible 字段，防止下游逻辑误认为媒体可见
                    clazz.getDeclaredField("visible")
                        .apply { isAccessible = true }
                        .setBoolean(thisObj, false)

                    Log.d(TAG, "setVisibility → forced GONE (master on)")
                } catch (e: Exception) {
                    Log.e(TAG, "setVisibility post-process failed", e)
                }
                return null
            }
        })

        // 3. onMediaHostVisibilityChanged(boolean) → 日志
        val onVisChanged = clazz.getDeclaredMethod(
            "onMediaHostVisibilityChanged",
            Boolean::class.javaPrimitiveType
        )
        hook(onVisChanged).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                val visible = chain.args[0] as Boolean
                Log.d(TAG, "onMediaHostVisibilityChanged: visible=$visible")
                return chain.proceed()
            }
        })

        Log.i(TAG, "KeyguardMediaController hooks registered ✓")
    }

    /**
     * Hook MediaHost.updateViewVisibility():
     * 锁屏 host (location == 0) 在主开关开启时强制 hostView GONE
     */
    private fun hookMediaHostVisibility(cl: ClassLoader) {
        val clazz = Class.forName(CLS_MEDIA_HOST, false, cl)
        val method = clazz.getDeclaredMethod("updateViewVisibility")

        hook(method).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                chain.proceed() // 先让原始逻辑执行
                if (!isMasterEnabled()) return null

                try {
                    val host = chain.thisObject
                    val location = clazz.getDeclaredField("location")
                        .apply { isAccessible = true }
                        .getInt(host)

                    if (location == HOST_LOCATION_LOCKSCREEN) {
                        val hostView = clazz.getDeclaredField("hostView")
                            .apply { isAccessible = true }
                            .get(host) as? View
                        hostView?.visibility = View.GONE
                        Log.d(TAG, "MediaHost.updateViewVisibility → GONE (lockscreen, master on)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaHost post-process failed", e)
                }
                return null
            }
        })

        Log.i(TAG, "MediaHost visibility hook registered ✓")
    }

    // ──────────────────────────────────────────────────
    //  Config
    // ──────────────────────────────────────────────────

    private fun isMasterEnabled(): Boolean {
        val ctx = systemUiContext ?: return false
        return HookPrefReader.isMasterEnabled(ctx)
    }
}
