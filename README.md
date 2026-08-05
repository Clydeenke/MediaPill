# MediaPill

把锁屏原生大媒体卡片替换为底部药丸控件（类似 One UI / Nothing OS / ColorOS）

> **LSPosed 模块** / Android 14–16 / AOSP-based ROM（LineageOS, crDroid 等）
> **仅支持 LSPosed（Zygisk 模式）· 不支持 EDXposed / 旧版 Xposed**

---

## Features

- 底部药丸：封面 + 滚动歌名 + 播放/暂停（~48dp 高）
- 点击展开完整控件（封面 + 进度条 + 上下曲 + 播放）
- 充电时自动上移避开系统充电信息
- 锁屏指纹验证时临时消失（避免遮挡指纹图案）
- 仅接管锁屏区域（不影响通知栏 / QuickSettings）

---

## Requirements

| 要求 | 版本 |
|---|---|
| Android | 14–16 (AOSP-based) |
| LSPosed | Zygisk 模式下最新版 |
| Root | KernelSU / APatch / Magisk ≥ 28 |

## Install

1. 到 [Releases](https://github.com/Clydeenke/MediaPill/releases) 下载最新 `.apk`
2. 安装 APK
3. LSPosed Manager → 模块 → 启用 MediaPill → 作用域勾选 **SystemUI (com.android.systemui)**
4. 重启

## Config

安装后打开 MediaPill 配置 App，可调整：

- 药丸纵向位置（78%–90%）
- 展开态背景模糊 / 透明度
- 默认播放器 appId 白名单

---

## 开发

项目基于 libxposed（API 102）+ Kotlin 2.4 + AGP 9。

```shell
./gradlew assembleDebug   # 编译为 Debug APK
```

**项目结构：**

```
app/
├── build.gradle.kts          # 模块构建配置
└── src/main/
    ├── AndroidManifest.xml   # LSPosed 入口声明
    ├── java/com/clydeenke/mediapill/
    │   ├── MainActivity.kt       # 配置 Activity（默认占位）
    │   └── xposed/
    │       └── PillHookEntry.kt  # SystemUI hook 入口（第一阶段）
    └── res/                      # 图标 + 主题资源
docs/
└── MEDIAPILL_FEASIBILITY.md    # 技术可行性评估（中文版）
```

---

## 免责 / 兼容性

本项目**仅承诺支持 AOSP 系 ROM**。MIUI、One UI、HarmonyOS 等深度定制系统因 SystemUI 内部结构差异，**不保证兼容**；请在 issue 前确认 ROM 类型。

---

## License

[MIT](LICENSE) © 2026 Clydeenke
