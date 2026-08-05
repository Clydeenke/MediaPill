package com.clydeenke.mediapill.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Xposed entry point.
 * LSPosed injects SystemUI process through this class.
 *
 * Current sprint:
 *   1. Verify injection (logcat print)
 *   2. Reflect-detect KeyguardMediaController / MediaHost real method signatures
 */
class PillHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MediaPill"
        private const val PACKAGE_SYSTEMUI = "com.android.systemui"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != PACKAGE_SYSTEMUI) return

        XposedHelpers.findAndHookMethod(
            "com.android.systemui.statusbar.StatusBar",
            lpparam.classLoader,
            "start",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    android.util.Log.i(TAG, "[Pill] SystemUI hooked successfully — phase 0 skeleton OK")
                }
            }
        )
    }
}

private typealias MethodHookParam = XC_MethodHook.MethodHookParam
