# MediaPill

LSPosed module — 把锁屏原生大媒体卡片替换为底部药丸控件

---

## Features

- 底部药丸：封面 + 滚动歌名 + 播放/暂停（~48dp high）
- 点击展开完整控件（封面 + 进度条 + 上下曲 + 播放）
- 充电时自动上移避开系统充电信息
- 锁屏指纹验证时临时消失
- 仅接管锁屏区域（不影响通知栏 / QS）

---

## Requirements

| Item | Version |
|---|---|
| Android | 14–16 (AOSP-based ROM) |
| LSPosed | Zygisk mode |
| Root | KernelSU / APatch / Magisk ≥ 28 |

## Install

1. Download latest `.apk` from [Releases](https://github.com/Clydeenke/MediaPill/releases)
2. Install APK
3. LSPosed Manager → Modules → Enable MediaPill → Scope: **SystemUI (com.android.systemui)**
4. Reboot

## Config

Open the MediaPill config app after install to adjust:

- Pill vertical position (78%–90%)
- Expanded state background blur / opacity
- Per-app whitelist

---

## Project Structure

```
app/
├── build.gradle.kts             # module build config
└── src/main/
    ├── AndroidManifest.xml      # LSPosed entry declaration
    ├── java/com/clydeenke/mediapill/
    │   ├── MainActivity.kt          # config activity (placeholder)
    │   └── xposed/
    │       └── PillHookEntry.kt     # SystemUI hook entry (phase 0)
    └── res/                         # icon + theme resources
docs/
└── MEDIAPILL_FEASIBILITY.md       # technical feasibility study
```

---

## Compat / Disclaimer

**AOSP-based ROM only** — MIUI / One UI / HarmonyOS etc. are not supported due to internal SystemUI divergence. Please check your ROM type before opening issues.

---

## Acknowledgments

- [libxposed](https://github.com/libposed/LibXposed) (API 102)
- [miuix-kmp](https://github.com/miuix-kotlin-multiplatform/miuix) — config UI components
- [AODTweaker](https://github.com/Clydeenke/AODTweaker) — same author's prior SystemUI hook project

---

## License

[MIT](LICENSE) © 2026 Clydeenke
