package com.clydeenke.mediapill.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPill Xposed 入口（libxposed API 102）。
 *
 * 阶段 1：探测 SystemUI 媒体控件真实结构 + 隐藏原生控件。
 * 所有日志走 android.util.Log（tag=MediaPill），便于 adb 直接排查。
 */
class PillHookEntry : XposedModule() {

    companion object {
        private const val TAG = "MediaPill"
        private const val PKG_SYSTEMUI = "com.android.systemui"
        private val registered = AtomicBoolean(false)

        // ── 探测目标类（LOS 23 dex 扫描确认的包路径） ──
        private val PROBE_TARGETS = arrayOf(
            ProbeTarget(
                name = "KeyguardMediaController",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.ui.controller.KeyguardMediaController",
                    "com.android.systemui.statusbar.notification.KeyguardMediaController",
                    "com.android.systemui.media.KeyguardMediaController"
                )
            ),
            ProbeTarget(
                name = "MediaHost",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.ui.view.MediaHost",
                    "com.android.systemui.media.MediaHost"
                )
            ),
            ProbeTarget(
                name = "MediaDataManager",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.domain.pipeline.MediaDataManager",
                    "com.android.systemui.media.MediaDataManager"
                )
            ),
            ProbeTarget(
                name = "MediaDataFilter",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl",
                    "com.android.systemui.media.MediaDataFilter"
                )
            ),
            ProbeTarget(
                name = "MediaHierarchyManager",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.ui.controller.MediaHierarchyManager",
                    "com.android.systemui.media.MediaHierarchyManager"
                )
            ),
            ProbeTarget(
                name = "MediaControlPanel",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.ui.controller.MediaControlPanel",
                    "com.android.systemui.media.MediaControlPanel"
                )
            ),
            ProbeTarget(
                name = "MediaCarouselController",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.ui.controller.MediaCarouselController",
                    "com.android.systemui.media.MediaCarouselController"
                )
            ),
            ProbeTarget(
                name = "SeekBarViewModel",
                candidates = arrayOf(
                    "com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel",
                    "com.android.systemui.media.SeekBarViewModel"
                )
            )
        )

        // ── SystemUI 启动入口候选（hook 探测触发点） ──
        private val SYSTEMUI_ENTRY_CANDIDATES = arrayOf(
            EntryCandidate(
                className = "com.android.systemui.statusbar.StatusBar",
                methodName = "start"
            ),
            EntryCandidate(
                className = "com.android.systemui.SystemUIApplication",
                methodName = "onCreate"
            )
        )
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded: process=${param.processName} " +
                "isSystemServer=${param.isSystemServer} " +
                "framework=$frameworkName($frameworkVersionCode) API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "onPackageReady: pkg=${param.packageName} first=${param.isFirstPackage}")
        if (param.packageName != PKG_SYSTEMUI) return
        if (!registered.compareAndSet(false, true)) {
            Log.i(TAG, "already registered, skip")
            return
        }
        Log.i(TAG, "===== MediaPill hook triggered =====")
        hookSystemuiStart(param)
    }

    // ──────────────────────────────────────────────────
    //  SystemUI 启动入口 hook（带降级）
    // ──────────────────────────────────────────────────

    private fun hookSystemuiStart(param: PackageReadyParam) {
        val cl = param.classLoader

        for (candidate in SYSTEMUI_ENTRY_CANDIDATES) {
            try {
                val clazz = Class.forName(candidate.className, false, cl)
                val method = clazz.getDeclaredMethod(candidate.methodName)
                hook(method).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        Log.i(TAG, "===== phase 1 probe start (via ${candidate.className}.${candidate.methodName}) =====")
                        runFullProbe(cl)
                        Log.i(TAG, "===== phase 1 probe end =====")
                        return chain.proceed()
                    }
                })
                Log.i(TAG, "hooked ${candidate.className}.${candidate.methodName}()")
                return
            } catch (e: ClassNotFoundException) {
                Log.i(TAG, "entry candidate not found: ${candidate.className}")
            } catch (e: NoSuchMethodException) {
                Log.i(TAG, "entry method not found: ${candidate.className}.${candidate.methodName}()")
            }
        }

        // 所有候选都失败 → 立即探测（不依赖启动 hook）
        Log.w(TAG, "! all entry candidates failed, probing immediately")
        runFullProbe(cl)
    }

    // ──────────────────────────────────────────────────
    //  完整探测
    // ──────────────────────────────────────────────────

    private fun runFullProbe(cl: ClassLoader) {
        // 1. 探测所有目标类
        for (target in PROBE_TARGETS) {
            probeClass(target, cl)
        }

        // 2. 探测 SystemUI 是否包含 Compose
        probeComposeAvailability(cl)

        // 3. 探测 MediaHost 的所有实例特征字段
        probeMediaHostFields(cl)
    }

    private fun probeClass(target: ProbeTarget, cl: ClassLoader) {
        Log.i(TAG, "--- ${target.name} ---")
        for (candidate in target.candidates) {
            try {
                val clazz = Class.forName(candidate, false, cl)
                Log.i(TAG, "FOUND: $candidate")
                dumpConstructors(clazz)
                dumpFields(clazz)
                dumpMethods(clazz)
                return
            } catch (_: ClassNotFoundException) {
                Log.i(TAG, "not this ROM: $candidate")
            }
        }
        Log.i(TAG, "! ${target.name} not found on any candidate path")
    }

    // ──────────────────────────────────────────────────
    //  探测 Compose 可用性
    // ──────────────────────────────────────────────────

    private fun probeComposeAvailability(cl: ClassLoader) {
        Log.i(TAG, "--- Compose availability ---")
        val composeClasses = arrayOf(
            "androidx.compose.runtime.Composable",
            "androidx.compose.ui.Modifier",
            "androidx.compose.material3.MaterialTheme",
            "androidx.compose.foundation.layout.Column"
        )
        for (cls in composeClasses) {
            try {
                Class.forName(cls, false, cl)
                Log.i(TAG, "✓ $cls available")
            } catch (_: ClassNotFoundException) {
                Log.i(TAG, "✗ $cls NOT available")
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  探测 MediaHost 字段（区分锁屏 host 的关键）
    // ──────────────────────────────────────────────────

    private fun probeMediaHostFields(cl: ClassLoader) {
        Log.i(TAG, "--- MediaHost field analysis ---")
        for (candidate in PROBE_TARGETS[1].candidates) {
            try {
                val clazz = Class.forName(candidate, false, cl)
                Log.i(TAG, "MediaHost fields (for instance identification):")
                for (field in clazz.declaredFields) {
                    Log.i(TAG, "  ${formatField(field)}")
                }
                // 也检查内部类
                for (inner in clazz.declaredClasses) {
                    Log.i(TAG, "  inner class: ${inner.simpleName}")
                    for (field in inner.declaredFields) {
                        Log.i(TAG, "    ${formatField(field)}")
                    }
                }
                return
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  反射 dump 工具
    // ──────────────────────────────────────────────────

    private fun dumpConstructors(clazz: Class<*>) {
        val ctors: Array<Constructor<*>> = clazz.declaredConstructors
        Log.i(TAG, "Constructors: ${ctors.size}")
        for (ctor in ctors) {
            val modStr = Modifier.toString(ctor.modifiers)
            val params = ctor.parameterTypes.joinToString(", ") { it.simpleName }
            Log.i(TAG, "  ▶ $modStr ${clazz.simpleName}($params)")
        }
    }

    private fun dumpFields(clazz: Class<*>) {
        val fields: Array<Field> = clazz.declaredFields
        Log.i(TAG, "Fields: ${fields.size}")
        for (field in fields) {
            Log.i(TAG, "  # ${formatField(field)}")
        }
    }

    private fun dumpMethods(clazz: Class<*>) {
        val allMethods: Array<Method> = clazz.declaredMethods
        Log.i(TAG, "Declared methods: ${allMethods.size}")
        for (method in allMethods) {
            val sig = formatMethod(method)
            val isInteresting = INTERESTING_KEYWORDS.any {
                method.name.contains(it, ignoreCase = true)
            }
            if (isInteresting) {
                Log.i(TAG, "  ★ $sig")
            }
        }
        // 也 dump 父类方法（可能有关键方法在父类）
        val superClass = clazz.superclass
        if (superClass != null && superClass != Any::class.java) {
            Log.i(TAG, "Super class: ${superClass.name}")
            val superMethods = superClass.declaredMethods
            for (method in superMethods) {
                val isInteresting = INTERESTING_KEYWORDS.any {
                    method.name.contains(it, ignoreCase = true)
                }
                if (isInteresting) {
                    Log.i(TAG, "  ★ [super] ${formatMethod(method)}")
                }
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  格式化工具
    // ──────────────────────────────────────────────────

    private fun formatMethod(method: Method): String {
        val modStr = Modifier.toString(method.modifiers)
        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
        val returnType = method.returnType.simpleName
        return "$modStr $returnType ${method.name}($params)"
    }

    private fun formatField(field: Field): String {
        val modStr = Modifier.toString(field.modifiers)
        val type = field.type.simpleName
        return "$modStr $type ${field.name}"
    }

    private val INTERESTING_KEYWORDS = arrayOf(
        "Visible", "hidden", "Visibility", "expansion",
        "attach", "detach", "show", "hide", "toggle",
        "state", "refresh", "host", "media", "keyguard",
        "controller", "callback", "listener", "update",
        "position", "progress", "seek", "play", "pause",
        "action", "artifact", "song", "artist", "app"
    )

    // ──────────────────────────────────────────────────
    //  数据类
    // ──────────────────────────────────────────────────

    private data class ProbeTarget(
        val name: String,
        val candidates: Array<String>
    )

    private data class EntryCandidate(
        val className: String,
        val methodName: String
    )
}
