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
 * Stage 1：隐藏原生媒体控件。
 *
 * 策略：主开关开启时，所有 MediaHost 返回不可见 + 所有 hostView 强制 GONE。
 * 不区分锁屏/QS/QQS——Stage 2 会在锁屏上添加自定义药丸控件。
 */
class PillHookEntry : XposedModule() {

    companion object {
        private const val TAG = "MediaPill"
        private const val PKG_SYSTEMUI = "com.android.systemui"
        private val registered = AtomicBoolean(false)

        private const val CLS_KEYGUARD_MEDIA_CTRL =
            "com.android.systemui.media.controls.ui.controller.KeyguardMediaController"
        private const val CLS_MEDIA_HOST =
            "com.android.systemui.media.controls.ui.view.MediaHost"
        private const val CLS_MEDIA_HIERARCHY_MGR =
            "com.android.systemui.media.controls.ui.controller.MediaHierarchyManager"

        @Volatile private var systemUiContext: Context? = null
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

    private fun registerStage1Hooks(cl: ClassLoader) {
        try { hookMediaHierarchyManager(cl) } catch (e: Exception) { Log.e(TAG, "hookMediaHierarchyManager failed", e) }
        try { hookMediaHost(cl) } catch (e: Exception) { Log.e(TAG, "hookMediaHost failed", e) }
        try { hookKeyguardMediaController(cl) } catch (e: Exception) { Log.e(TAG, "hookKeyguardMediaController failed", e) }
    }

    // ──────────────────────────────────────────────────
    //  MediaHierarchyManager: 捕获 Context
    // ──────────────────────────────────────────────────

    private fun hookMediaHierarchyManager(cl: ClassLoader) {
        val clazz = Class.forName(CLS_MEDIA_HIERARCHY_MGR, false, cl)

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
                            Log.i(TAG, "Context captured from MediaHierarchyManager")
                        }
                    } catch (e: Exception) { Log.e(TAG, "capture context failed", e) }
                }
                return null
            }
        })

        Log.i(TAG, "MediaHierarchyManager hooks registered ✓")
    }

    // ──────────────────────────────────────────────────
    //  MediaHost: getVisible + updateViewVisibility + hostView GONE
    // ──────────────────────────────────────────────────

    private fun hookMediaHost(cl: ClassLoader) {
        val clazz = Class.forName(CLS_MEDIA_HOST, false, cl)
        val hostViewField = clazz.getDeclaredField("hostView").apply { isAccessible = true }

        // getVisible() → 主开关开启时返回 false
        val getVisibleMethod = clazz.getDeclaredMethod("getVisible")
        hook(getVisibleMethod).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (!isMasterEnabled()) return chain.proceed()
                Log.d(TAG, "getVisible() → false")
                return false
            }
        })

        // updateViewVisibility() → 主开关开启时跳过原始逻辑，直接 GONE
        val updateVisMethod = clazz.getDeclaredMethod("updateViewVisibility")
        hook(updateVisMethod).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (!isMasterEnabled()) return chain.proceed()
                try {
                    val hostView = hostViewField.get(chain.thisObject) as? View
                    hostView?.visibility = View.GONE
                    Log.d(TAG, "updateViewVisibility → GONE")
                } catch (e: Exception) { Log.e(TAG, "forceGone failed", e) }
                return null
            }
        })

        Log.i(TAG, "MediaHost hooks registered ✓")
    }

    // ──────────────────────────────────────────────────
    //  KeyguardMediaController: refreshMediaPosition + setVisibility
    // ──────────────────────────────────────────────────

    private fun hookKeyguardMediaController(cl: ClassLoader) {
        val clazz = Class.forName(CLS_KEYGUARD_MEDIA_CTRL, false, cl)

        // 构造函数 → 备用 Context
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
                    } catch (e: Exception) { Log.e(TAG, "capture context failed", e) }
                }
                return null
            }
        })

        // refreshMediaPosition → 强制 visible=false
        try {
            val refreshMethod = clazz.getDeclaredMethod("refreshMediaPosition", String::class.java)
            hook(refreshMethod).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    chain.proceed()
                    if (!isMasterEnabled()) return null
                    try {
                        clazz.getDeclaredField("visible")
                            .apply { isAccessible = true }
                            .setBoolean(chain.thisObject, false)
                        Log.d(TAG, "refreshMediaPosition → visible=false")
                    } catch (e: Exception) { Log.e(TAG, "refreshMediaPosition failed", e) }
                    return null
                }
            })
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "refreshMediaPosition not found, skipping")
        }

        // setVisibility → 强制 GONE
        val setVisMethod = clazz.getDeclaredMethod("setVisibility",
            Int::class.javaPrimitiveType, ViewGroup::class.java)
        hook(setVisMethod).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                chain.proceed()
                if (!isMasterEnabled()) return null
                try {
                    val container = chain.args[1] as? ViewGroup
                    container?.visibility = View.GONE
                    clazz.getDeclaredField("visible")
                        .apply { isAccessible = true }
                        .setBoolean(chain.thisObject, false)
                    Log.d(TAG, "setVisibility → forced GONE")
                } catch (e: Exception) { Log.e(TAG, "setVisibility failed", e) }
                return null
            }
        })

        Log.i(TAG, "KeyguardMediaController hooks registered ✓")
    }

    // ──────────────────────────────────────────────────
    //  Config
    // ──────────────────────────────────────────────────

    private fun isMasterEnabled(): Boolean {
        val ctx = systemUiContext ?: return false
        return HookPrefReader.isMasterEnabled(ctx)
    }
}
