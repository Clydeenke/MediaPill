package com.clydeenke.mediapill.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * Stage 2：在锁屏注入药丸 View + 订阅 MediaDataManager。
 *
 * 注入策略（双通道）：
 *   A. KeyguardMediaController 构造函数 → 延迟搜索字段中的 View → 找根视图 → 注入
 *   B. setVisibility(int, ViewGroup) 任意调用 → 用 container 参数找根视图 → 注入
 *
 * 显隐规则：只靠 StatusBarState + Doze 状态控制，不依赖 setVisibility。
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
        private const val CLS_MEDIA_DATA_MGR =
            "com.android.systemui.media.controls.domain.pipeline.MediaDataManager"
        private const val CLS_STATUS_BAR_STATE_CTRL =
            "com.android.systemui.statusbar.StatusBarStateControllerImpl"

        private const val STATE_SHADE = 0
        private const val STATE_KEYGUARD = 1
        private const val STATE_SHADE_LOCKED = 2

        @Volatile private var systemUiContext: Context? = null
        @Volatile private var overlayController: PillOverlayController? = null
        @Volatile private var savedClassLoader: ClassLoader? = null
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded: process=${param.processName} " +
                "framework=$frameworkName($frameworkVersionCode) API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != PKG_SYSTEMUI) return
        if (!registered.compareAndSet(false, true)) return
        Log.i(TAG, "===== MediaPill Stage 1+2: registering hooks =====")
        savedClassLoader = param.classLoader
        registerStage1Hooks(param.classLoader)
        registerStage2Hooks(param.classLoader)
    }

    // ──────────────────────────────────────────────────
    //  Stage 1: 隐藏原生媒体控件
    // ──────────────────────────────────────────────────

    private fun registerStage1Hooks(cl: ClassLoader) {
        try { hookMediaHierarchyManager(cl) } catch (e: Exception) { Log.e(TAG, "hookMediaHierarchyManager failed", e) }
        try { hookMediaHost(cl) } catch (e: Exception) { Log.e(TAG, "hookMediaHost failed", e) }
        try { hookKeyguardMediaController(cl) } catch (e: Exception) { Log.e(TAG, "hookKeyguardMediaController failed", e) }
    }

    // ──────────────────────────────────────────────────
    //  Stage 2
    // ──────────────────────────────────────────────────

    private fun registerStage2Hooks(cl: ClassLoader) {
        try { hookStatusBarStateController(cl) } catch (e: Exception) { Log.e(TAG, "hookStatusBarStateController failed", e) }
        try { hookNotificationPanelViewController(cl) } catch (e: Exception) { Log.e(TAG, "hookNotificationPanelViewController failed", e) }
        try { hookScreenState(cl) } catch (e: Exception) { Log.e(TAG, "hookScreenState failed", e) }
        try { hookKeyguardUpdateMonitor(cl) } catch (e: Exception) { Log.e(TAG, "hookKeyguardUpdateMonitor failed", e) }
        try { hookWallpaperChange(cl) } catch (e: Exception) { Log.e(TAG, "hookWallpaperChange failed", e) }
        // hookMediaDataManager 已移除：MediaDataManager 无声明构造函数，会抛 ArrayIndexOutOfBoundsException。
        // MediaDataManager 通过 MediaHierarchyManager 构造函数参数捕获。
    }

    private fun ensureOverlayController(): PillOverlayController? {
        val ctx = systemUiContext ?: return null
        val cl = savedClassLoader ?: return null
        if (overlayController == null) {
            overlayController = PillOverlayController(ctx)
            overlayController!!.init(cl)
            Log.i(TAG, "PillOverlayController initialized")
        }
        return overlayController
    }

    // ──────────────────────────────────────────────────
    //  MediaHierarchyManager: 捕获 Context + MediaDataManager
    // ──────────────────────────────────────────────────

    private fun hookMediaHierarchyManager(cl: ClassLoader) {
        val clazz = Class.forName(CLS_MEDIA_HIERARCHY_MGR, false, cl)
        val constructors = clazz.declaredConstructors
        if (constructors.isEmpty()) {
            Log.w(TAG, "MediaHierarchyManager has no declared constructors, skipping")
            return
        }
        hook(constructors[0]).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                chain.proceed()
                val mgr = chain.thisObject

                // 捕获 Context
                if (systemUiContext == null) {
                    try {
                        val ctx = clazz.getDeclaredField("context")
                            .apply { isAccessible = true }
                            .get(mgr) as? Context
                        if (ctx != null) {
                            systemUiContext = ctx
                            Log.i(TAG, "Context captured from MediaHierarchyManager")
                            ensureOverlayController()
                        }
                    } catch (e: Exception) { Log.e(TAG, "capture context failed", e) }
                }

                // 捕获 MediaDataManager（从构造函数参数中搜索）
                val oc = ensureOverlayController()
                if (oc != null && !oc.hasMediaDataManager()) {
                    val listenerCls = try {
                        Class.forName(
                            "com.android.systemui.media.controls.domain.pipeline.MediaDataManager\$Listener",
                            false, cl
                        )
                    } catch (_: Exception) { null }

                    if (listenerCls != null) {
                        for (arg in chain.args) {
                            try {
                                arg?.javaClass?.getDeclaredMethod("addListener", listenerCls)
                                Log.i(TAG, "MediaDataManager found in MediaHierarchyManager ctor: ${arg.javaClass.name}")
                                oc.setMediaDataManager(arg, cl)
                                break
                            } catch (_: NoSuchMethodException) { }
                        }
                    }
                }
                return null
            }
        })
        Log.i(TAG, "MediaHierarchyManager hooks registered ✓")
    }

    // ──────────────────────────────────────────────────
    //  MediaHost: 隐藏原生
    // ──────────────────────────────────────────────────

    private fun hookMediaHost(cl: ClassLoader) {
        val clazz = Class.forName(CLS_MEDIA_HOST, false, cl)
        val hostViewField = clazz.getDeclaredField("hostView").apply { isAccessible = true }

        hook(clazz.getDeclaredMethod("getVisible")).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (!isMasterEnabled()) return chain.proceed()
                return false
            }
        })

        hook(clazz.getDeclaredMethod("updateViewVisibility")).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (!isMasterEnabled()) return chain.proceed()
                try {
                    (hostViewField.get(chain.thisObject) as? View)?.visibility = View.GONE
                } catch (e: Exception) { Log.e(TAG, "forceGone failed", e) }
                return null
            }
        })
        Log.i(TAG, "MediaHost hooks registered ✓")
    }

    // ──────────────────────────────────────────────────
    //  KeyguardMediaController: 隐藏原生 + 注入药丸
    // ──────────────────────────────────────────────────

    private fun hookKeyguardMediaController(cl: ClassLoader) {
        val clazz = Class.forName(CLS_KEYGUARD_MEDIA_CTRL, false, cl)

        val constructors = clazz.declaredConstructors
        if (constructors.isEmpty()) {
            Log.w(TAG, "KeyguardMediaController has no declared constructors, skipping")
            return
        }

        // ── 通道 A：构造函数 → 延迟搜索字段 → 注入 ──
        hook(constructors[0]).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                chain.proceed()
                val controller = chain.thisObject

                // 捕获 Context（备用）
                if (systemUiContext == null) {
                    try {
                        val ctx = clazz.getDeclaredField("context")
                            .apply { isAccessible = true }
                            .get(controller) as? Context
                        if (ctx != null) {
                            systemUiContext = ctx
                            Log.i(TAG, "Context captured from KeyguardMediaController")
                            ensureOverlayController()
                        }
                    } catch (e: Exception) { Log.e(TAG, "capture context failed", e) }
                }

                // 延迟注入：等视图层级构建完毕后搜索 View 字段
                mainHandler.postDelayed({
                    tryInjectFromFields(controller, clazz)
                }, 2000)

                // 再延迟一次（保险）
                mainHandler.postDelayed({
                    tryInjectFromFields(controller, clazz)
                }, 5000)

                return null
            }
        })

        // refreshMediaPosition → 强制 visible=false + 隐藏 hostView
        try {
            hook(clazz.getDeclaredMethod("refreshMediaPosition", String::class.java)).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    chain.proceed()
                    if (!isMasterEnabled()) return null
                    try {
                        clazz.getDeclaredField("visible")
                            .apply { isAccessible = true }
                            .setBoolean(chain.thisObject, false)
                        // 额外：尝试隐藏关联的 MediaHost
                        hideAssociatedHosts(clazz, chain.thisObject)
                    } catch (e: Exception) { Log.e(TAG, "refreshMediaPosition failed", e) }
                    return null
                }
            })
        } catch (_: NoSuchMethodException) { }

        // ── 通道 B：setVisibility 任意调用 → 隐藏原生 + 注入药丸 ──
        hook(clazz.getDeclaredMethod("setVisibility",
            Int::class.javaPrimitiveType, ViewGroup::class.java)).intercept(object : Hooker {
            override fun intercept(chain: Chain): Any? {
                val container = chain.args[1] as? ViewGroup
                chain.proceed()

                // Stage 1：无论主开关状态如何都隐藏原生控件（更激进）
                // 因为用户可能先开开关再锁屏，此时需要立即生效
                try {
                    container?.visibility = View.GONE
                    // 同时隐藏所有子 View
                    if (container is ViewGroup && isMasterEnabled()) {
                        for (i in 0 until container.childCount) {
                            container.getChildAt(i).visibility = View.GONE
                        }
                    }
                    clazz.getDeclaredField("visible")
                        .apply { isAccessible = true }
                        .setBoolean(chain.thisObject, false)
                } catch (e: Exception) { Log.e(TAG, "Stage1 hide in setVisibility failed", e) }

                if (!isMasterEnabled()) return null

                // Stage 2：注入药丸
                if (container != null) {
                    val oc = ensureOverlayController()
                    if (oc != null && !oc.isInjected()) {
                        val root = findRootViewGroup(container)
                        if (root != null) {
                            Log.i(TAG, "Found root view from setVisibility: ${root.javaClass.name}")
                            root.post { oc.inject(root) }
                        }
                    }
                }
                return null
            }
        })

        // ── 通道 C：onMediaHostVisibilityChanged → 强制隐藏 ──
        // 某些 ROM 通过此方法控制可见性
        try {
            hook(clazz.getDeclaredMethod("onMediaHostVisibilityChanged", Boolean::class.javaPrimitiveType))
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        if (!isMasterEnabled()) return chain.proceed()
                        // 不调用原始方法，直接返回
                        Log.d(TAG, "onMediaHostVisibilityChanged blocked")
                        return null
                    }
                })
            Log.i(TAG, "Hooked onMediaHostVisibilityChanged ✓")
        } catch (_: NoSuchMethodException) {
            Log.d(TAG, "onMediaHostVisibilityChanged not found, skipping")
        }

        Log.i(TAG, "KeyguardMediaController hooks registered ✓")
    }

    /**
     * 尝试隐藏与控制器关联的 MediaHost 的 hostView。
     */
    private fun hideAssociatedHosts(controllerClass: Class<*>, controller: Any) {
        for (field in controllerClass.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(controller) ?: continue
                // 如果是 MediaHost 类型，取其 hostView 并 GONE
                if (value.javaClass.name.contains("MediaHost")) {
                    try {
                        val hostView = value.javaClass.getDeclaredField("hostView")
                            .apply { isAccessible = true }
                            .get(value) as? View
                        if (hostView != null) {
                            hostView.visibility = View.GONE
                            Log.d(TAG, "Force GONE associated hostView from field '${field.name}'")
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    // ──────────────────────────────────────────────────
    //  从控制器字段中搜索 View → 找根视图 → 注入
    // ──────────────────────────────────────────────────

    private fun tryInjectFromFields(controller: Any, clazz: Class<*>) {
        val oc = ensureOverlayController() ?: return
        if (oc.isInjected()) return

        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(controller) ?: continue

                // 直接是 View
                if (value is View && value.parent != null) {
                    val root = findRootViewGroup(value)
                    if (root != null) {
                        Log.i(TAG, "Found root view from field '${field.name}': ${root.javaClass.name}")
                        root.post { oc.inject(root) }
                        return
                    }
                }

                // 是 MediaHost → 取 hostView 字段
                if (value.javaClass.name.contains("MediaHost")) {
                    try {
                        val hostView = value.javaClass.getDeclaredField("hostView")
                            .apply { isAccessible = true }
                            .get(value) as? View
                        if (hostView != null && hostView.parent != null) {
                            val root = findRootViewGroup(hostView)
                            if (root != null) {
                                Log.i(TAG, "Found root view from MediaHost '${field.name}': ${root.javaClass.name}")
                                root.post { oc.inject(root) }
                                return
                            }
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
        Log.d(TAG, "tryInjectFromFields: no suitable View found yet")
    }

    // ──────────────────────────────────────────────────
    //  StatusBarStateController: 锁屏状态 + Doze(AOD) 状态
    // ──────────────────────────────────────────────────

    private fun hookStatusBarStateController(cl: ClassLoader) {
        val clazz = Class.forName(CLS_STATUS_BAR_STATE_CTRL, false, cl)

        // 1. Hook setState(int, boolean) — 锁屏/解锁状态
        var stateHooked = false
        for (name in listOf("setState", "setBarState", "updateState")) {
            try {
                hook(clazz.getDeclaredMethod(name, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            handleStatusBarStateChanged(chain.args[0] as Int)
                            return result
                        }
                    })
                Log.i(TAG, "Hooked StatusBarStateController.$name(int, boolean) ✓")
                stateHooked = true
                break
            } catch (_: NoSuchMethodException) { }

            try {
                hook(clazz.getDeclaredMethod(name, Int::class.javaPrimitiveType))
                    .intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            handleStatusBarStateChanged(chain.args[0] as Int)
                            return result
                        }
                    })
                Log.i(TAG, "Hooked StatusBarStateController.$name(int) ✓")
                stateHooked = true
                break
            } catch (_: NoSuchMethodException) { }
        }

        if (!stateHooked) {
            Log.w(TAG, "Could not hook StatusBarState setState")
        }

        // 2. Hook setDozeAmountInternal(float) — AOD/Doze 状态
        // 阈值 0.5 防抖：唤醒动画期间 amount 会快速振荡
        try {
            hook(clazz.getDeclaredMethod("setDozeAmountInternal", Float::class.javaPrimitiveType))
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        val amount = chain.args[0] as Float
                        val oc = ensureOverlayController() ?: return result
                        val dozing = amount > 0.5f
                        oc.onDozeStateChanged(dozing)
                        return result
                    }
                })
            Log.i(TAG, "Hooked StatusBarStateController.setDozeAmountInternal ✓")
        } catch (_: NoSuchMethodException) {
            Log.w(TAG, "setDozeAmountInternal not found, AOD detection disabled")
        }

        // 不 hook isDozing()：它被 SystemUI 高频调用，会导致状态疯狂闪烁
    }

    private fun handleStatusBarStateChanged(state: Int) {
        Log.i(TAG, "StatusBarState: $state")
        val oc = ensureOverlayController() ?: return
        when (state) {
            STATE_KEYGUARD, STATE_SHADE_LOCKED -> oc.onKeyguardStateChanged(true)
            STATE_SHADE -> oc.onKeyguardStateChanged(false)
        }
    }

    // ──────────────────────────────────────────────────
    //  NotificationPanelViewController: 状态栏展开监听
    // ──────────────────────────────────────────────────

    private fun hookNotificationPanelViewController(cl: ClassLoader) {
        val classNames = listOf(
            "com.android.systemui.statusbar.phone.NotificationPanelViewController",
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.PanelViewController"
        )

        for (className in classNames) {
            try {
                val clazz = Class.forName(className, false, cl)

                // 1. setExpandedHeight / setPanelExpanded 方法 — 监听状态栏展开
                val methods = listOf("setExpandedHeight", "setPanelExpanded", "onPanelExpansionChanged")
                for (methodName in methods) {
                    try {
                        val method = clazz.getDeclaredMethod(methodName, Float::class.javaPrimitiveType)
                        hook(method).intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                val value = chain.args[0] as Float
                                val oc = ensureOverlayController() ?: return result

                                // 判断是高度值还是比例值
                                val isFraction = value <= 1.0f && methodName == "onPanelExpansionChanged"
                                val expanded = if (isFraction) {
                                    value > 0.05f  // 比例 > 5% 认为展开
                                } else {
                                    value > dp(50f)  // 高度 > 50dp 认为展开
                                }

                                oc.onShadeExpandedChanged(expanded)

                                // 如果在锁屏且展开，同时触发控制中心打开（某些 ROM 控制中心就是状态栏）
                                if (expanded && oc.isOnKeyguard()) {
                                    oc.onControlCenterChanged(true)
                                } else if (!expanded) {
                                    oc.onControlCenterChanged(false)
                                }

                                return result
                            }
                        })
                        Log.i(TAG, "Hooked $className.$methodName ✓")
                        return
                    } catch (_: NoSuchMethodException) { }
                }

                // 2. 监听 QS 展开
                try {
                    hook(clazz.getDeclaredMethod("setQsExpanded", Boolean::class.javaPrimitiveType))
                        .intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                val expanded = chain.args[0] as Boolean
                                ensureOverlayController()?.onQsExpandedChanged(expanded)
                                // QS 展开也视为控制中心打开
                                if (expanded) {
                                    ensureOverlayController()?.onControlCenterChanged(true)
                                }
                                return result
                            }
                        })
                    Log.i(TAG, "Hooked $className.setQsExpanded ✓")
                } catch (_: NoSuchMethodException) { }

            } catch (_: ClassNotFoundException) { }
        }

        // 备用：Hook KeyguardStatusBarView 的展开状态
        try {
            val clazz = Class.forName("com.android.systemui.statusbar.phone.KeyguardStatusBarView", false, cl)
            hook(clazz.getDeclaredMethod("onPanelExpansionChanged", Float::class.javaPrimitiveType))
                .intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        val fraction = chain.args[0] as Float
                        val expanded = fraction > 0.05f
                        val oc = ensureOverlayController()
                        oc?.onShadeExpandedChanged(expanded)
                        if (expanded && oc?.isOnKeyguard() == true) {
                            oc.onControlCenterChanged(true)
                        } else if (!expanded) {
                            oc?.onControlCenterChanged(false)
                        }
                        return result
                    }
                })
            Log.i(TAG, "Hooked KeyguardStatusBarView.onPanelExpansionChanged ✓")
        } catch (_: Exception) { }
    }

    private fun dp(v: Float): Float = v * (systemUiContext?.resources?.displayMetrics?.density ?: 3f)

    // ──────────────────────────────────────────────────
    //  KeyguardUpdateMonitor: Bouncer（密码输入界面）监听
    // ──────────────────────────────────────────────────

    private fun hookKeyguardUpdateMonitor(cl: ClassLoader) {
        val classNames = listOf(
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.statusbar.phone.KeyguardUpdateMonitor"
        )

        for (className in classNames) {
            try {
                val clazz = Class.forName(className, false, cl)

                // Hook onKeyguardBouncerStateChanged
                try {
                    hook(clazz.getDeclaredMethod("onKeyguardBouncerStateChanged", Boolean::class.javaPrimitiveType))
                        .intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                val result = chain.proceed()
                                val showing = chain.args[0] as Boolean
                                ensureOverlayController()?.onBouncerStateChanged(showing)
                                return result
                            }
                        })
                    Log.i(TAG, "Hooked $className.onKeyguardBouncerStateChanged ✓")
                } catch (_: NoSuchMethodException) {
                    // 备用：Hook sendKeyguardBouncerChanged
                    try {
                        hook(clazz.getDeclaredMethod("sendKeyguardBouncerChanged", Boolean::class.javaPrimitiveType))
                            .intercept(object : Hooker {
                                override fun intercept(chain: Chain): Any? {
                                    val result = chain.proceed()
                                    val showing = chain.args[0] as Boolean
                                    ensureOverlayController()?.onBouncerStateChanged(showing)
                                    return result
                                }
                            })
                        Log.i(TAG, "Hooked $className.sendKeyguardBouncerChanged ✓")
                    } catch (_: NoSuchMethodException) { }
                }

                return
            } catch (_: ClassNotFoundException) { }
        }

        Log.w(TAG, "KeyguardUpdateMonitor not found, Bouncer detection disabled")
    }

    // ──────────────────────────────────────────────────
    //  屏幕状态监听
    // ──────────────────────────────────────────────────

    private fun hookScreenState(cl: ClassLoader) {
        // Hook PowerManager 的屏幕状态变化
        val classNames = listOf(
            "com.android.systemui.statusbar.phone.ScreenOffAnimationController",
            "com.android.systemui.statusbar.phone.StatusBar",
            "com.android.systemui.keyguard.KeyguardViewMediator"
        )

        for (className in classNames) {
            try {
                val clazz = Class.forName(className, false, cl)

                // 屏幕打开
                try {
                    hook(clazz.getDeclaredMethod("onScreenTurnedOn")).intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            ensureOverlayController()?.onScreenStateChanged(true)
                            return result
                        }
                    })
                    Log.i(TAG, "Hooked $className.onScreenTurnedOn ✓")
                } catch (_: NoSuchMethodException) { }

                // 屏幕关闭
                try {
                    hook(clazz.getDeclaredMethod("onScreenTurnedOff")).intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            ensureOverlayController()?.onScreenStateChanged(false)
                            return result
                        }
                    })
                    Log.i(TAG, "Hooked $className.onScreenTurnedOff ✓")
                } catch (_: NoSuchMethodException) { }

                // 唤醒完成
                try {
                    hook(clazz.getDeclaredMethod("onWakeAndUnlocking")).intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            ensureOverlayController()?.onScreenStateChanged(true)
                            return result
                        }
                    })
                    Log.i(TAG, "Hooked $className.onWakeAndUnlocking ✓")
                } catch (_: NoSuchMethodException) { }

            } catch (_: ClassNotFoundException) { }
        }
    }

    // ──────────────────────────────────────────────────
    //  壁纸变化监听
    // ──────────────────────────────────────────────────

    private fun hookWallpaperChange(cl: ClassLoader) {
        // 监听 KeyguardUpdateMonitor 的壁纸变化回调
        val classNames = listOf(
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.statusbar.phone.KeyguardUpdateMonitor"
        )

        for (className in classNames) {
            try {
                val clazz = Class.forName(className, false, cl)

                // Hook onWallpaperChanged
                try {
                    hook(clazz.getDeclaredMethod("onWallpaperChanged")).intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            Log.i(TAG, "Wallpaper changed, refreshing blur")
                            mainHandler.postDelayed({
                                overlayController?.refreshBlur()
                            }, 500) // 延迟 500ms 确保壁纸已应用
                            return result
                        }
                    })
                    Log.i(TAG, "Hooked $className.onWallpaperChanged ✓")
                } catch (_: NoSuchMethodException) { }

                return
            } catch (_: ClassNotFoundException) { }
        }

        Log.w(TAG, "KeyguardUpdateMonitor not found, wallpaper change detection disabled")
    }

    // ──────────────────────────────────────────────────
    //  Utils
    // ──────────────────────────────────────────────────

    private fun findRootViewGroup(view: View): ViewGroup? {
        var current: View = view
        var parent = current.parent
        var depth = 0
        while (parent is View && depth < 30) {
            current = parent
            parent = current.parent
            depth++
        }
        return if (current is ViewGroup) current else null
    }

    private fun isMasterEnabled(): Boolean {
        // 使用缓存值，主线程零 IPC（避免 ANR）
        return HookPrefReader.masterEnabled
    }
}
