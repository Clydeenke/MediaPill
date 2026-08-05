# MediaPill

把鎖屏原生大媒體卡片替換為底部藥丸控件（類似 One UI / Nothing OS / ColorOS）

> **LSPosed 模块** / Android 14–16 / AOSP-based ROM（LineageOS, crDroid 等）
> **僅支持 LSPosed（Zygisk 模式）· 不支持 EDXposed / 舊版 Xposed**

---

## Features

- 底部藥丸：封面 + 滾動歌名 + 播放/暫停（~48dp 高）
- 點擊展開完整控件（封面 + 進度條 + 上下首 + 播放）
- 充電時自動上移避開系統充電信息
- 鎖屏指紋驗證時臨時消失（避免遮擋指紋圖案）
- 僅接管鎖屏區域（不影響通知欄 / QuickSettings）

---

## Requirements

| 要求 | 版本 |
|---|---|
| Android | 14–16 (AOSP-based) |
| LSPosed | Zygisk 模式下最新版 |
| Root | KernelSU / APatch / Magisk ≥ 28 |

## Install

1. 到 [Releases](https://github.com/Clydeenke/MediaPill/releases) 下載最新 `.apk`
2. 安裝 APK
3. LSPosed Manager → 模組 → 啟用 MediaPill → 作用域勾選 **SystemUI (com.android.systemui)**
4. 重啟

## Config

安裝後打開 MediaPill 配置 App，可調整：

- 藥丸縱向位置（78%–90%）
- 展開態背景模糊 / 透明度
- 默認播放器appId 白名單

---

## 開發

項目基於 libxposed（API 102）+ Kotlin 2.4 + AGP 9。

```shell
./gradlew assembleDebug   # 編譯為 Debug APK
```

**項目結構：**

```
app/
├── build.gradle.kts          # 模塊構建配置
└── src/main/
    ├── AndroidManifest.xml   # LSPosed 入口聲明
    ├── java/com/clydeenke/mediapill/
    │   ├── MainActivity.kt       # 配置 Activity（默認占位）
    │   └── xposed/
    │       └── PillHookEntry.kt  # SystemUI hook 入口（第一階段）
    └── res/                      # 圖標 + 主題資源
docs/
└── MEDIAPILL_FEASIBILITY.md    # 技術可行性評估（中文版）
```

---

## 免責 / 兼容性

本項目**僅承諾支持 AOSP 系 ROM**。MIUI、One UI、HarmonyOS 等深度定制系統因 SystemUI 內部結構差異，**不保證兼容**；請在 issue 前確認 ROM 類型。

---

## 致謝 / 依賴

- [libxposed](https://github.com/libposed/LibXposed) (API 102) — LSPosed 框架
- [miuix-kmp](https://github.com/miuix-kotlin-multiplatform/miuix) — 配置 UI 組件
- [AODTweaker](https://github.com/Clydeenke/AODTweaker) — 同一作者的 SystemUI hook 實踐參考

---

## License

[MIT](LICENSE) © 2026 Clydeenke
