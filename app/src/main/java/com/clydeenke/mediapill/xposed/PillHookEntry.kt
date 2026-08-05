package com.clydeenke.mediapill.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Xposed entry point for MediaPill.
 *
 * Phase 0: logcat proof-of-injection
 * Phase 1 (current): reflect-detect real method signatures
 *                   on KeyguardMediaController and MediaHost.
 *
 * Usage: after installing the apk and enabling in LSPosed,
 *        run `adb logcat -s MediaPill` to see probe output.
 */
class PillHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MediaPill"
        private const val PKG_SYSTEMUI = "com.android.systemui"

        private val KEYGUARD_MEDIA_CANDIDATES = arrayOf(
            "com.android.systemui.media.controls.ui.controller.KeyguardMediaController",
            "com.android.systemui.statusbar.notification.KeyguardMediaController",
            "com.android.systemui.media.KeyguardMediaController"
        )

        private val MEDIA_HOST_CANDIDATES = arrayOf(
            "com.android.systemui.media.controls.ui.view.MediaHost",
            "com.android.systembar.notification.MediaHost",
            "com.android.systemui.media.MediaHost"
        )
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != PKG_SYSTEMUI) return

        XposedHelpers.findAndHookMethod(
            "com.android.systemui.statusbar.StatusBar",
            lpparam.classLoader,
            "start",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("[$TAG] ===== phase 1 probe start =====")
                    probeKeyguardMediaController(lpparam.classLoader)
                    probeMediaHost(lpparam.classLoader)
                    XposedBridge.log("[$TAG] ===== phase 1 probe end =====")
                }
            }
        )
    }

    private fun probeKeyguardMediaController(classLoader: ClassLoader) {
        XposedBridge.log("[$TAG] --- KeyguardMediaController candidates ---")
        for (candidate in KEYGUARD_MEDIA_CANDIDATES) {
            try {
                val clazz = Class.forName(candidate, false, classLoader)
                XposedBridge.log("[$TAG] FOUND: $candidate")
                dumpMethods(clazz)
                return
            } catch (_: ClassNotFoundException) {
                XposedBridge.log("[$TAG] not this ROM: $candidate")
            }
        }
        XposedBridge.log("[$TAG] ! KeyguardMediaController not found on any candidate path")
    }

    private fun probeMediaHost(classLoader: ClassLoader) {
        XposedBridge.log("[$TAG] --- MediaHost candidates ---")
        for (candidate in MEDIA_HOST_CANDIDATES) {
            try {
                val clazz = Class.forName(candidate, false, classLoader)
                XposedBridge.log("[$TAG] FOUND: $candidate")
                dumpMethods(clazz)
                return
            } catch (_: ClassNotFoundException) {
                XposedBridge.log("[$TAG] not this ROM: $candidate")
            }
        }
        XposedBridge.log("[$TAG] ! MediaHost not found on any candidate path")
    }

    private fun dumpMethods(clazz: Class<*>) {
        val allMethods = clazz.declaredMethods
        XposedBridge.log("[$TAG] Declared methods: ${allMethods.size}")
        val interestingKeywords = arrayOf(
            "Visible", "hidden", "Visibility", "expansion",
            "attach", "detach", "show", "hide", "toggle",
            "state", "refresh", "host", "media", "keyguard",
            "controller", "callback", "listener", "update"
        )
        var interestingCount = 0
        for (method in allMethods) {
            val sig = formatMethod(method)
            val isInteresting = interestingKeywords.any { method.name.contains(it, ignoreCase = true) }
            if (isInteresting) {
                interestingCount++
                XposedBridge.log("[$TAG] ★ $sig")
            }
        }
        XposedBridge.log("[$TAG] Interesting methods: $interestingCount")
    }

    private fun formatMethod(method: Method): String {
        val modStr = Modifier.toString(method.modifiers)
        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
        val returnType = method.returnType.simpleName
        return "$modStr $returnType ${method.name}($params)"
    }
}

private typealias MethodHookParam = XC_MethodHook.MethodHookParam
