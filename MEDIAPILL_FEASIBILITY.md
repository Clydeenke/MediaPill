# MediaPill — 锁屏媒体控件药丸化 可行性评估

> 本文档为**独立新项目**的可行性评估，供后续开发（可由其他 AI 直接依据本文档实施）参考。
> 与 AODTweaker 分离，避免把两个不同 hook 域塞进一个模块，也利于项目命名清晰。

## 1. 目标

把锁屏（及可选 AOD）的媒体控件从「顶部大卡片 / 通知列表内嵌」改为**屏幕底部药丸**（类似 One UI / ColorOS / 灵动岛风格）：

- **空闲态**：底部居中显示一个药丸，含应用图标 + 歌曲名（滚动）+ 播放/暂停按钮
- **展开态**：点击药丸向上展开为完整控件（封面 + 标题/艺术家 + 进度条 + 上一首/下一首/播放 + 可选收藏/输出设备）
- **不挡壁纸**：默认只占底部一小条，展开时才向上铺开
- **AOD 可选**：AOD 下显示极简药丸（只有图标 + 播放状态），点击不展开（AOD 触摸受限）

## 2. 总体难度评估

**高。** 这不是一个"hook 一个方法就能搞定"的模块，本质是在 SystemUI 进程里**自绘一套媒体 UI 并接管原生媒体控件的显示**。难度集中在三处：

1. **隐藏原生锁屏媒体控件**而不破坏通知布局（`KeyguardMediaController` 是通知列表的特殊 wrapper）
2. **自己渲染药丸 + 展开控件**，需要处理触摸、动画、状态同步、横竖屏、刘海/导航栏避让
3. **媒体数据订阅**：从 `MediaDataManager` 拿实时数据（曲目、封面、播放状态、进度），还要处理多播放器 carousel 场景

对比 AODTweaker（hook 状态机 + 读配置，无自绘 UI），这个项目的工作量大约是它的 **3–5 倍**，且 ROM 兼容性风险更高（媒体控件布局在不同 AOSP 版本间变动较大）。

> 参考案例：[punch-hole-download-progress#21](https://github.com/hxreborn/punch-hole-download-progress/issues/21) 的维护者评估类似"灵动岛"需求时说："a massive undertaking, essentially a different app altogether"，建议独立成项目。与本评估结论一致。
>
> ## 2.2 位置策略：药丸 × 充电信息 × 指纹图标（已调研 One UI / ColorOS / Nothing OS）
>
> **冲突域**（Xiaomi 12 数据）：
> | 元素 | 屏幕纵轴占比 | 触发条件 |
> |---|---|---|
> | 充电锁屏信息 | 93–97% | 充电器插入 + 屏幕处于锁屏 |
> | 屏内指纹验证图标 | 58–65% | 手指接触传感器 |
> | 药丸最佳视觉位（初始） | 88–92% | 无充电的正常锁屏 |
>
> **竞品处理方案**：
> - **One UI**（三星）：充电信息不在底部——三星把充电百分比做成一个大圆环放在**屏幕中央偏下**（约 70% 纵轴），药丸默认位置是底部偏上（约 85%）。两者天然不冲突。**药丸位置固定不变**。
> - **ColorOS / OriginOS**（OPPO）：药丸做得比较高（约 75–80%），且充电状态干脆**收起药丸变成顶部一条细线**，充电完成后再恢复。避免任何竞争。
> - **Nothing OS**：药丸放在底部但**整个锁屏做了分区**——指纹区在下方 1/3 底部有一个明确的圆圈，药丸永远只在圆圈上方（约 80%）。
>
> **本项目推荐策略**（参考 One UI + ColorOS 组合思路）：
>
> | 场景 | 药丸纵轴位置 | 行为 | 备注 |
> |---|---|---|---|
> | 正常锁屏（非充电） | 90% | 显示完整药丸 | 视觉最佳位置 |
> | 充电中 + 锁屏 | 78% | 上移至充电信息上方 | 通过 `ACTION_BATTERY_CHANGED` 动态调整 |
> | 指纹验证中 | — | 临时 alpha=0 / GONE | 监听 `KeyguardUpdateMonitor.onFingerprintRunningStateChanged`（LOS 有此回调） |
> | 关机充电模式 | — | 不显示（锁屏界面不可见） | 例外情况不用处理 |
>
> **附加：暴露配置给用户**。在 miuix-kmp 配置界面中加一个"纵向位置（%）"滑块，默认 78–90 可调。让用户自行微调避免刘海/曲面屏的视觉问题——One UI 和 Nothing OS 也都提供类似选项。
>
> **实现要点**：
> - 用 `WindowManager.LayoutParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL` + 动态计算 `yOffset`。充电判断通过注册 `IntentFilter(Intent.ACTION_BATTERY_CHANGED)` + `BatteryManager.isCharging` 拿到。
> - 指纹监听的 hook 点：`KeyguardUpdateMonitor.java` 第 2000+ 行的 `notifyFingerprintRunningState(int type)` 方法（实施时反射确认签名）。
>
> ## 2.1 已验证的设备信息（2026-08-05 实机确认）
>
> - 设备：Xiaomi 2201122G（Xiaomi 12），已 Root（`su` 可用）
> - OS：LineageOS 23（Android 16, SDK 36），底层固件为澎湃 OS 3.0/安卓 15 的 Xiaomi 基带——**基改不了，但上层完全由 LOS 接管，SystemUI 是纯 AOSP 结构**
> - SystemUI 路径：`/system_ext/priv-app/SystemUI/SystemUI.apk`（已用 dex 字符串扫描验证）
> - 媒体类结构与 AOSP 高度一致（`com.android.systemui.media.controls.ui.controller.KeyguardMediaController`、`MediaHost`、`MediaDataManager$Listener`、`MediaControlPanel`、`SeekBarViewModel` 等全部存在）
> - 存在 `LegacyMediaDataManagerImpl` + `MediaDataFilterImpl` 双管线，LOS 兼容新老两种媒体数据流
> - `MediaOutputDialogManager` + `MediaOutputSwitcherDialogUI` 负责输出设备弹窗——AOSP 原版没有这个分离，LOS 做了拆分

## 3. SystemUI 媒体控件架构（hook 点定位）

以下基于 AOSP `packages/SystemUI/src/com/android/systemui/media/` 的源码结构（Android 14–16 路径基本稳定，新版可能多一层 `controls/ui/controller` 子包）。

> **LOS 23 实际包路径（dex 扫描确认）**：All classes live under `com.android.systemui.media.controls.` 子包。具体路径示例：
> - 数据层：`com.android.systemui.media.controls.domain.pipeline.*`
> - UI 层 controller：`com.android.systemui.media.controls.ui.controller.*`
> - UI 层 viewModel：`com.android.systemui.media.controls.ui.viewmodel.*`
> - DI 模块：`com.android.systemui.media.dagger.MediaModule`、`com.android.systemui.media.controls.domain.MediaDomainModule`
> - 输出设备弹窗：`com.android.systemui.media.dialog.MediaOutputDialogManager`（LOS 从原 MediaOutputDialog 中分离出来的独立管理器）

### 3.1 数据层（拿媒体数据）

| 类 | 完整包路径（LOS 实机确认） | 职责 | hook 用途 |
|---|---|---|---|
| `MediaDataManager` | `media.controls.domain.pipeline.MediaDataManager`（现代管线）/ `LegacyMediaDataManagerImpl`（兼容旧 app 的管线） | 管线入口。把媒体通知 / 可恢复媒体转成 `MediaData`，分发给 listener | **数据源**。LOS 两条管线并行推荐走现代管线注册 `MediaDataManager.Listener` |
| `MediaDataFilter` | `media.controls.domain.pipeline.MediaDataFilterImpl`（现代管线）/ `LegacyMediaDataFilterImpl`（兼容管线） | 按当前用户过滤，管线"出口"。外部 listener 实际听的是它 | 推荐通过 `MediaDataManager.addListener()` 注册，内部会接到 filter |
| `MediaData` | `media.controls.shared.model.MediaData` | 数据载体：曲目、艺术家、封面、**playbackState.position（可直接读实时进度）**、resumption、actions 列表等 | `data.playbackState.position` 拿初始进度；`data.actions` 拿播放控制 intent |
| `MediaDeviceManager` | `media.controls.domain.pipeline.MediaDeviceManager` | 输出设备信息（蓝牙/扬声器） | 展开态显示输出设备用（二期）；按钮点击拉起 `MediaOutputDialogManager` |

### 3.2 UI 层（原生媒体控件，要隐藏/接管的对象）

| 类 | 完整包路径（LOS 实机确认） | 职责 | hook 用途 |
|---|---|---|---|
| `MediaHost` | `media.controls.ui.view.MediaHost` + `MediaHost$MediaHostStateHolder` | **每个位置一个实例**（锁屏、通知栏、QS、AOD）。stateHolder 持有 expansion/visibility | **关键 hook 点**。锁屏的 MediaHost 控制原生控件显隐——需先 hook 构造方法截获所有实例再区分哪个是锁屏 |
| `KeyguardMediaController` | `media.controls.ui.controller.KeyguardMediaController` + `KeyguardMediaControllerLogger` | 锁屏专用 wrapper，让媒体控件塞进锁屏通知布局 | **隐藏入口**。hook `setVisible`/`setExpansion`/`refresh` 方法（字符串已确认存在） |
| `MediaCarouselController` | `media.controls.ui.controller.MediaCarouselController` + `MediaCarouselControllerLogger` + `MediaCarouselScrollHandler` | carousel 容器，管理多个播放器 + 展开态 | 了解即可，MVP 隐藏后不参与 |
| `MediaControlPanel` | `media.controls.ui.controller.MediaControlPanel` | 单个播放器的完整 UI（展开态的样子） | 参考其布局自绘，或直接复用其 View |
| `MediaHierarchyManager` | `media.controls.ui.controller.MediaHierarchyManager` | 负责媒体 view 的放置和 host 间动画 | **状态迁移监听**——上滑解锁 / 通知栏下拉时媒体 host 切换，需在此回调中同步药丸显隐 |
| `MediaViewController` | `media.controls.ui.controller.MediaViewController`（含 `configurationListener`/`stateCallback`） | 控制单个媒体 view 的状态 | 了解即可 |
| `SeekBarViewModel` | `media.controls.ui.viewmodel.SeekBarViewModel`（含 `Progress` 数据类） | 进度条计算（`computePosition`） | 进度条平滑动画直接照搬 |

### 3.3 关键结论

- **锁屏媒体控件是独立的一套**（`KeyguardMediaController` + 锁屏 `MediaHost`），和通知栏的媒体控制分开。这给了我们干净的 hook 切入点：只接管锁屏这一个 host，不影响通知栏 QS 里的媒体。
- 数据走 `MediaDataManager.Listener`，**不需要自己 hook 通知或 MediaSession**——SystemUI 已经把媒体通知解析成 `MediaData` 了，直接订阅即可。

## 4. 推荐技术方案：隐藏原生 + 自绘药丸

经过对比（见 §5），推荐 **方案 A**：隐藏原生锁屏媒体控件，自己画一个药丸。

### 4.1 整体架构（沿用 AODTweaker 双进程模型）

```
┌──────────────────────────────────────────────────┐
│  App 进程（com.mediapill）                        │
│  Compose UI（miuix-kmp）配置开关 + 预览          │
│       ↕ RemotePreferences (跨进程)               │
└──────────────────────┬───────────────────────────┘
                       │ IPC
┌──────────────────────┴───────────────────────────┐
│  SystemUI 进程（com.android.systemui）            │
│                                                   │
│  MediaPillHookEntry (XposedModule 入口)           │
│       ↓                                           │
│  1. KeyguardMediaHider                            │
│     hook KeyguardMediaController / 锁屏 MediaHost │
│     → 隐藏原生锁屏媒体控件                        │
│                                                   │
│  2. MediaDataSubscriber                           │
│     注册 MediaDataManager.Listener                │
│     → 拿到 MediaData（曲目/封面/状态）            │
│                                                   │
│  3. PillOverlayController                         │
│     在锁屏根容器注入自绘药丸 View                 │
│     → 空闲态药丸 / 点击展开完整控件               │
│                                                   │
│  4. PillView (自绘, 纯 View 或 ComposeView)       │
│     数据绑定 + 动画 + 触摸                        │
└───────────────────────────────────────────────────┘
```

### 4.2 三大模块详解

#### 模块 1：隐藏原生锁屏媒体控件（KeyguardMediaHider）

目标：让锁屏上不再出现原生的大媒体卡片，但**数据管线照常运行**（我们还要订阅它）。

hook 点候选（**实施前必须反射探测设备真实 dex**，AOSP 源码只能当起点，R8 可能内联/改名）：

1. **`KeyguardMediaController` 的可见性方法**：找类似 `setVisible` / `refresh` / `attach` 的方法，after-hook 里把媒体 view 设为 `GONE`，或让方法直接 return。
2. **锁屏 `MediaHost` 的 `hostView`**：把 `MediaHost.hostView` 的可见性压成 `GONE`，或 hook `MediaHost.setExpansion` / `MediaHost.setShowsCurrentActiveMedia` 强制不显示。
3. **`MediaHierarchyManager`** 的可见性调度方法。

**风险**：`KeyguardMediaController` 在不同版本签名差异大，且它和锁屏通知布局（`NotificationStackScrollLayout`）的高度计算耦合——直接 GONE 媒体 view 后，通知列表的 top padding / 留白可能对不齐。需要同时调整通知列表的测量，否则锁屏顶部可能出现空白。

**降级策略**：如果隐藏不掉原生控件，可退而求其次——只 hook `MediaControlPanel` 把原生卡片**改造成药丸样式**（方案 B），不隐藏不重绘。

#### 模块 2：订阅媒体数据（MediaDataSubscriber）

```kotlin
// 伪代码：注册 listener 拿实时媒体数据
val mediaDataManager = resolve(MediaDataManager::class.java)  // SystemUI 用 DI，需从合适的 Context/组件拿实例
mediaDataManager.addListener(object : MediaDataManager.Listener {
    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        // data.appLabel, data.song, data.artist, data.artworkBitmap
        // data.playbackState (playing/paused), data.actions (MediaAction 列表)
        pillController.updateMedia(data)
    }
    override fun onMediaDataRemoved(key: String) {
        pillController.hideMedia(key)
    }
})
```

**进度条**：`MediaData.playbackState`（`PlaybackState` 对象）是包含 `position` 字段的，基础进度信息可以直接读——文档原版说法"不直接给实时进度"不够准确。`SeekBarViewModel.computePosition()` 的作用是**平滑动画**（避免频繁 binder 调用刷新 UI），并非"没有数据只能用算法算"。实现时：进度初始值取自 `MediaData.playbackState.position`，后续平滑推进照搬 `SeekBarViewModel.computePosition()` 算法。

**多播放器 / carousel**：MVP **只显示最近活跃的一个**，不做 carousel 切换。`MediaDataFilterImpl` 是按当前活跃会话过滤的管线出口（AOSP 原版 `MediaDataFilter`），直接通过它注册就能拿到"当前最近一个"，不需要自己做合并逻辑。

**实例获取**：SystemUI 用 Dagger/Dagger2 DI。`MediaDataManager` 不能 `new`，要通过 hook 拿到 SystemUI 的 `Dependency` / `SystemUIFactory` 入口，或 hook 某个持有它引用的类的构造方法把它"截获"出来存成静态引用。AODTweaker 没遇到 DI 问题（hook 的 `DozeMachine` 是从 `transitionTo` 的 `this` 直接拿的），这里是**本项目的新难点**。

#### 模块 3：自绘药丸 + 展开控件（PillOverlayController + PillView）

**注入位置**：找到锁屏根容器（`KeyguardRootView` / `NotificationPanelView` 的子容器），在底部加一个 `FrameLayout` 承载药丸。要确保：
- 只在锁屏可见时显示（hook `KeyguardViewMediator` 或监听 `KeyguardUpdateMonitor` 的 `onKeyguardVisibilityChanged`）
- 层级在壁纸之上、状态栏之下，避免遮挡状态栏
- 避让导航栏 / 手势条（`WindowInsets` 的 navigation bar）
- 横竖屏 / 折叠屏重新布局

**药丸 View 实现**：**先探测目标 SystemUI classpath 中 `androidx.compose.*` 决策用纯 View 还是 ComposeView**。LOS 23 的 SystemUI 已内置 `androidx.compose:compose-bom` 依赖，直接 `ComposeView` + `ViewCompositionStrategy` 比纯 View 动画/触摸代码量少 2–3 倍。如果目标 ROM 没有 Compose（老旧 AOSP 14 定制），降级纯 View（XML + Canvas）。**推荐先 Compose 开发、纯 View 做兜底分支**，而非直接放弃 Compose。

**展开动画**：药丸高度从 ~48dp 动画到 ~360dp，同时淡入完整控件。用 `ValueAnimator` + `TransitionManager`，或简单的 `ViewPropertyAnimator`。

**触摸**：药丸区域 `setOnClickListener` 切换展开/收起；展开态内的按钮各自 `onClick` 通过 `MediaData.actions` 的 `Action`（`RemoteAction` / pending intent）触发播放控制——**直接复用 `MediaData` 里现成的 action intent**，不用自己连 MediaSession。播放/暂停/上下曲的 action 都在 `MediaData.actions` 列表中；输出设备按钮则会拉起 `MediaOutputDialogManager`（LOS 拆分出来的组件），MVP 可以不响应此按钮或留二期。

### 4.3 AOD 上的药丸（可选，二期）

AOD 下系统本身可能不显示完整媒体控件（取决于 ROM）。要做 AOD 药丸：
- AOD 渲染走 `DozeMachine` + AOD 布局，和锁屏不是同一套 view 树
- 需要在 AOD 布局里再注入一个药丸 view
- AOD 触摸受限（doze 状态下点击不一定传递），展开交互可能不可行，只能显示静态药丸

**建议**：AOD 药丸作为二期功能，MVP 只做锁屏。

## 5. 备选方案对比

| | 方案 A：隐藏+自绘 | 方案 B：改造原生布局 | 方案 C：普通应用悬浮窗 |
|---|---|---|---|
| 实现方式 | GONE 原生控件 + 自绘药丸注入锁屏 | hook 媒体 view 的 LayoutParams，压扁移到底部 | 普通 app + SYSTEM_ALERT_WINDOW 悬浮窗 |
| 视觉控制 | 完全自由，干净药丸 | 受原生 view 结构限制，难做成药丸 | 完全自由 |
| 锁屏可见性 | 天然可见（在 SystemUI 进程） | 天然可见 | 需 `FLAG_SHOW_WHEN_LOCKED`，且可能被锁屏遮挡/层级问题 |
| 触摸/动画 | 完全可控 | 受原生控件触摸逻辑约束 | 可控但要处理和锁屏的触摸冲突 |
| ROM 兼容 | 中（hook 点需探测，自绘部分稳定） | 差（原生媒体 view 布局各 ROM 差异大） | 好（不依赖 SystemUI 内部） |
| 数据获取 | SystemUI 内直接订阅 MediaDataManager | 同 A | 要自己 hook 通知 / MediaController，复杂 |
| 工作量 | 大 | 中 | 大（数据层要重写） |

**结论**：方案 A 最契合"干净药丸 + 不挡壁纸 + 锁屏原生可见"的需求。方案 C（悬浮窗 app）虽然兼容性好，但拿媒体数据要重新造轮子，且锁屏层级体验差，违背"原生集成"初衷。**选方案 A。**

## 6. 实施步骤（建议顺序）

### 阶段 0：环境与脚手架（1 天）
1. 新建项目 `MediaPill`，复制 AODTweaker 的 Gradle 骨架（libxposed API 102 + miuix-kmp + RemotePreferences）
2. XposedModule 入口，`onPackageReady` 匹配 `com.android.systemui`
3. 配置 UI 脚手架（开关：启用模块 / 药丸样式 / 展开控件项）

### 阶段 1：探测 + 隐藏原生控件（5–8 天，最不确定。该阶段每多一天，后续阶段风险越低）
1. 反射遍历 `com.android.systemui.media.controls` 包，打印 `KeyguardMediaController`、`MediaHost`、`MediaViewController` 的真实方法签名（字符串已从 dex 确认包路径，定位具体方法即可）
2. hook `MediaHost` 构造方法截获所有 Host 实例，通过特征字段（context 类型、parent hostView 层级、expansion 状态）区分哪个是锁屏 host
3. 尝试 GONE 原生媒体 view，验证锁屏顶部不留白（若留白，需 hook 通知列表的 padding/测量；注意 AOSP 14 和 15+ 测量逻辑不同，patch 点要分别探测）
4. **这一步如果卡住，整个方案要降级到方案 B。建议在阶段 1 投入时间不设硬上限——这是整个项目的最大风险点，宁可花 8 天攻克，不要仓促降级后发现是假阴性**

### 阶段 2：订阅媒体数据（2 天）
1. 截获 `MediaDataManager` 实例（DI 入口探测）
2. 注册 listener，logcat 打印 `MediaData` 字段确认数据正确
3. 处理多 session：只保留最近活跃的一个

### 阶段 3：自绘药丸（2–3 天）
1. 在锁屏根容器注入 `FrameLayout`，放一个 48dp 药丸 view（图标 + 歌名滚动 + 播放按钮）
2. 数据绑定 + 播放/暂停按钮调 `MediaData.actions`
3. 锁屏显隐联动（`KeyguardUpdateMonitor` 监听）

### 阶段 4：展开控件（2 天）
1. 点击药丸 → 动画展开到完整控件（封面 + 标题 + 进度条 + 上下首/播放）
2. 进度条照搬 `SeekBarViewModel.computePosition()` 逻辑
3. 点击空白 / 上滑收起

### 阶段 5：打磨（2 天）
1. 横竖屏 / 刘海 / 导航栏避让
2. 动画曲线、圆角、配色对齐系统风格
3. 无媒体时药丸消失的过渡

### 阶段 6（二期）：AOD 药丸
- 在 AOD 布局注入极简药丸，仅显示图标 + 播放状态，无展开

**预计总工期**：MVP（阶段 0–5）约 **20–25 天**全职（阶段 1 隐藏控件通常是最耗时的，含 lock/unlock 状态机调试）。阶段 1 卡住的每一天都是必要投入——后续阶段依赖它的输出。ROM 兼容性调试可追加 5–8 天。

## 7. 主要风险与坑

| 风险 | 说明 | 应对 |
|---|---|---|
| **隐藏原生控件留白** | `KeyguardMediaController` 和通知列表测量耦合，GONE 后顶部可能空白。注意 Android 14 vs 15+ 高度计算逻辑不同（15+ 引入 `MediaContainerScrollBehavior`），点也不同 | hook 通知列表的 padding/测量；需在 14/15+ 分别探测 patch 点；实在不行降级方案 B |
| **DI 实例拿不到** | SystemUI 用 Dagger，`MediaDataManager` 无法 new | hook 持有它的类的构造方法截获；或 hook `Dependency.get()` 类入口 |
| **MediaHost 实例区分** | SystemUI 有多个 `MediaHost`（锁屏、通知栏、QS、AOD），hook 构造方法截获到的实例需要先识别哪个是锁屏——判断特征：host 的 context 类型、`hostView` 的 parent 是否是锁屏根容器、或者根据 `host.expansion`/sysfs 状态特征过滤 | 截获所有 MediaHost 实例后反射读取其特征字段做分类 |
| **R8 内联改名** | LOS 16 SystemUI 经过 R8，方法可能被内联/删除（AODTweaker 已踩过，见 TECHNICAL_NOTES 坑 1） | 所有 hook 点先反射探测真实 dex |
| **状态机过渡动画闪烁** | 解锁中→锁屏消失、UI view 重建、通知栏下拉接管媒体归属（从锁屏 host 迁移到通知栏 host），任一环节时序不对会闪烁/残留 | 参考 `MediaDomainModule` 中状态迁移 hook，确保在 host 切换时同步隐藏/恢复药丸；统一在 `KeyguardUpdateMonitor` + `MediaHierarchyManager` 回调中双向监听 |
| **进度条不准** | 不轮询 PlaybackState 要自己算位置 | 照搬 `SeekBarViewModel.computePosition()`，基础数据取自 `MediaData.playbackState.position` |
| **多播放器** | carousel 场景处理复杂 | MVP 只显示最近活跃的一个，走 `MediaDataFilterImpl` 注册 |
| **锁屏触摸层级** | 药丸要在锁屏可点击，但不能挡住上滑解锁 | 测量锁屏手势区域，药丸只占底部安全区 |
| **ROM 差异** | 小米/三星/Pixel 的 SystemUI media 实现差异大 | 只承诺支持 AOSP 系（LineageOS/crDroid），明确不支持 MIUI/One UI 等深度定制 |

## 8. MVP 范围建议（第一个可发布版本）

**做**：
- 锁屏底部药丸（空闲态：图标 + 歌名 + 播放/暂停）
- 点击展开完整控件（封面 + 标题/艺术家 + 进度条 + 上下首/播放）
- 单播放器（不处理 carousel）
- 配置开关：启用 / 药丸高度 / 展开时显示进度条
- 支持 AOSP 系 ROM（Android 14–16）

**不做**（留后续版本）：
- AOD 药丸
- 多播放器 carousel 切换
- 输出设备切换 / 收藏等扩展按钮
- 自定义药丸颜色/主题（先用系统配色）
- 非 AOSP ROM 支持

## 9. 可复用 AODTweaker 的经验

- **libxposed API 102 的项目骨架**：`XposedModule` 入口、`onPackageReady`、`ExceptionMode.PROTECTIVE`、RemotePreferences 跨进程配置
- **反射探测设备真实 dex 的习惯**（TECHNICAL_NOTES 坑 1 的教训）
- **RemotePreferences 跨进程 listener 不可靠**的教训——本项目如果需要 hook 端实时响应配置变更，同样采用"主动读取"而非依赖 listener
- **懒加载 SystemUI Context** 的经验（`ActivityThread.currentApplication()` 反射 + 懒加载）
- **miuix-kmp 配置 UI** 的整套封装

## 10. 参考资料

- AOSP SystemUI 媒体控件管线文档：`packages/SystemUI/docs/media-controls.md`
- AOSP 源码：`packages/SystemUI/src/com/android/systemui/media/`
- libxposed example：https://github.com/libxposed/example
- AODTweaker TECHNICAL_NOTES（本项目同作者的 SystemUI hook 踩坑记录）
- punch-hole-download-progress#21（类似灵动岛需求的可行性讨论）

## 11. 给执行 AI 的建议

1. **先做阶段 1（探测+隐藏）再投入其他模块**——这是最大不确定性所在，验证不了就降级方案 B 或重新评估
2. **所有 hook 点反射探测真实 dex**，别信 AOSP 源码签名
3. **先探测目标 ROM SystemUI classpath 是否包含 `androidx.compose`**——有那就 ComposeView 优先，纯 XML 兜底；没有再降级纯 View
4. **先 logcat 打印 MediaData + MediaHost 实例列表**，确认数据结构和各 host 归属再写 UI 绑定
5. **进度条初始值取 `MediaData.playbackState.position`**，平滑动画照抄 `SeekBarViewModel.computePosition()`
6. **锁屏显隐 + 解锁过渡 + 通知栏接管统一监听**：组合使用 `KeyguardUpdateMonitor` + `MediaHierarchyManager` host 切换回调，避免闪烁/残留
7. **MediaHost 实例区分是个坑**：截获所有实例后通过 context 类型、parent 层级、expansion 状态等字段识别锁屏 host
8. **墨绿色输出设备按钮**：MVP 应让按钮不可见或置灰，因为输出设备弹窗 (`MediaOutputDialogManager`) 后续需单独处理——actions[] 里可能找不到 "output" 对应 action 时是正常的
9. 测试设备：Xiaomi 12（2201122G）· Android 16 · LineageOS 23 · Root 已就绪。重要：底层基带为澎湃 OS 3.0 不可变（`AQ3A.250226.002`），但上层完全由 LOS 接管

---

## 12. GitHub 发布规范 & 维护约定（给 0 基础入门）

> 这一节专门写给"之前没正经做过 GitHub 开源"的同学。每一条"为什么这么做"都在后面注释出来了。

### 12.1 语义化版本号（Semantic Versioning，简称 SemVer）

**格式**：`MAJOR.MINOR.PATCH`（例：`1.2.3`）

**核心规则**：

| 何时递增 | 递增哪个数字 | 例子 | 适用场景 |
|---|---|---|---|
| 改了对外承诺的 API / 破坏性变化 | MAJOR（大版本） | `1.5.0` → `2.0.0` | LSPosed scope 大改、移除老 ROM 支持 |
| 加了新功能，但向后兼容 | MINOR（中版本） | `1.1.0` → `1.2.0` | 新增"纵向位置滑块"、"手势切歌" |
| 修了 bug，没加新功能 | PATCH（小版本） | `1.1.0` → `1.1.1` | 修复动画掉帧、修复进度条不准 |

**一句话记忆**："大版本 = 坏了会通知用户、中版本 = 加东西、小版本 = 静默修 bug"。

**本项目版本策略**：
- 第一个 push 用 `0.1.0`——这只是给项目"占个坑"，告诉别人"我还没承诺稳定性"，给自己改 API 的灵活度
- 你自己的主力机实测 7+ 天 + 至少 1 人帮你测了没提 bug → 升 `1.0.0`
- AODTweaker 直接 1.0.0 是因为它本身就稳定。MediaPill 比它复杂得多，先 0.1.0 更诚实

> **学到的东西**：SemVer 不是强制的，但是社区约定——你如果发了 `2.0.0`，依赖你的人就知道"我需要看更新日志"。不加这个规范的项目，使用者不敢升级，最终跑的都是老旧版本。

### 12.2 README 结构（GitHub 上人们看一个项目的顺序）

```
# MediaPill                    ← 项目名（一级标题）
一句话说清楚这是什么            ← 每个 GitHub 项目必须有的一句话介绍

## 截图 / GIF                  ← 第一印象决定关注度（30 秒内判断装不装）
## 功能                      ← 3-5 个 bullet，不能多
## 安装                      ← 怎么装、依赖什么框架（LSPosed？root？）
## 配置 / 使用                ← 装完怎么打开配置、怎么用
## 兼容设备 / ROM              ← 诚实说清楚"只支持"，避免被 issue 轰炸
## 已知问题                     ← 坦诚比隐瞒更拉好感
## 构建                       ← 开发者如何从源码编译
## 致谢 / 许可                  ← 你用了什么依赖、Mit/Apache/GPL 选哪个
```

**最小可用 README 模板**（开工第一阶段只需要填前 5 节）：

```markdown
# MediaPill
把锁屏原生大媒体卡片替换为底部药丸控件（类似 Nothing OS / One UI）

> 仅支持 LineageOS / crDroid / AOSP（Android 14-16），不承诺 MIUI / One UI 等深度定制

## Features
- 底部药丸：图标 + 滚动歌名 + 播放/暂停
- 点击展开完整控件（封面 + 进度条 + 上下首/播放）
- 充电时自动上移避开系统充电信息
- 仅接管锁屏（不影响通知栏 / QS 媒体）

## Requirements
- Android 14-16 / AOSP-based ROM
- LSPosed（Zygisk 模式）
- Root

## Install
1. 从 [Releases](https://github.com/你的名/MediaPill/releases) 页面下载最新 .apk
2. 安装 APK
3. 打开 LSPosed → 模块 → 启用 MediaPill → 作用域勾选 `SystemUI (com.android.systemui)`
4. 重启

## 配置
安装后打开 MediaPill 配置 App，按提示调整药丸位置和展示内容

## 已知问题
- 偶尔解锁过程中药丸闪烁（正在修）
- 关机充电模式不显示（预期行为）
```

> **学到的东西**：README 是项目的"脸"，GitHub 有研究说 90% 的人通过 README 决定要不要点进来看代码。写好 README 就是写好第一印象。

### 12.3 开源协议（LICENSE）

三选一，按自由度排序（推荐绿色 = 最推荐的）：

| 协议 | 含义 | 适合 | 自由度 |
|---|---|---|---|
| **MIT** | "随便用，保留版权声明即可" | 大多数 Xposed 模块 | ⭐⭐⭐⭐⭐ 最自由 |
| **Apache 2.0** | 类似 MIT + 明确专利授权 | Google 风格的项目 | ⭐⭐⭐⭐ |
| **GPLv3** | "用了我的代码的项目也必须开源" | 强制衍生作品开源 | ⭐⭐⭐ |

**本项目推荐：MIT**——Xposed 模块社区大多数用 MIT。加 LICENSE 文件到仓库根目录就行，GitHub 创建仓库时可以自动选。

> **学到的东西**：没加 LICENSE 的代码默认是"版权所有，不得转载"——这在 GitHub 上是个坑，别人不敢用你的代码。加一行 MIT 就是宣誓"欢迎 fork 欢迎提 PR"。

### 12.4 GitHub Actions CI（自动化编译）

项目每次 push，GitHub 服务器帮你跑一次编译/检查，确保主干永远可以编译通过。

最小 CI 配置（`.github/workflows/ci.yml`）：

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build
        run: ./gradlew assembleDebug
```

**这能做什么**：
- 你忘了改 build 文件导致编译挂了 → PR 直接红灯
- 有人提了 PR 但你没空看 → CI 跑一遍没问题可以 trust
- 每次发 Release 都自动编译出 APK → 不用手动签名再上传

> **学到的东西**：CI 是"替你守门的自动化守卫"。没有 CI 的项目，不知道哪次 push 后人家的 fork 就编译不过了。有了 CI，你的 main 分支永远是可用的。

### 12.5 分支策略（分支管理的最低限度规则）

| 分支 | 用途 | 何时 push |
|---|---|---|
| `main` | 主干，永远是"能工作的版本" | 仅阶段里程碑 |
| `dev-*` | 开发中分支（如 `dev-pill-ui`、`dev-hide-controller`） | 日常开发 |
| `fix-*` | bug 修复分支 | 修 bug |

**黄金规则：不要直接在 `main` 上改代码。** 每次开发都从 `main` 切 `dev-xxx`，开发完测试没问题后合回 `main`。

> **学到的东西**：这叫"Git Flow 简化版"。AODTweaker 也是单兵作战可能不需要这么严格，但养成了这个习惯后团队协作会很顺，而且在多项目同时维护时也帮你避免"昨天改了什么"的记忆混乱。

### 12.6 CHANGELOG.md（变更日志）

每次发新版本记录 `docs/CHANGELOG.md`：

```markdown
## [0.1.0] - 2026-08-XX
### Added
- 药丸空闲态显示（图标 + 滚动歌名 + 播放按钮）
- 播放/暂停按钮响应
- 充电自动上移
```

格式参考 [Keep a Changelog](https://keepachangelog.com/) 规范。GitHub Release 的时候把 CHANGELOG 里的内容复制过去。

> **学到的东西**：CHANGELOG 不是写给自己看的，是写给"两个月后的自己"和"提 issue 的用户"看的——"为什么上次能用现在不行了？去看 CHANGELOG /issues/42"。

### 12.7 .gitignore

```
# 必须有的条目
*.iml
.gradle/
local.properties
.idea/
build/
captures/
.externalNativeBuild/
*.apk      # 版本发布通过 GitHub Releases 管理，APK 不进 git
*.keystore # 签名文件绝不上传
```

> **学到的东西**：`.gitignore` 是"我需要 git 忽略的文件列表"程。不配置的话，build 过中的临时文件也会被推到仓库——GitHub 项目充斥几百个 .class 文件和几个 G 的 .gradle 缓存，别人 clone 下来要几分钟。`*.keystore` 不进 git 是因为私钥泄露 = 整个模块签名被别人伪造。

### 12.8 后续同模块项目方向（作品集扩展路线）

做完药丸主体后，以下功能都复用同一个 `MediaDataManager.Listener` 和 `MediaOutputDialogManager` hook，属于零边际成本扩展：

1. **状态栏图标隐藏**（隐藏电池图标/网络图标/SIM 图标 → 和更干净的锁屏视觉统一）
2. **通知卡片重布局**（把通知列表里的媒体卡片改成类似药丸的样式→ 跨 host 视觉统一）
3. **截屏 / 录屏绕过 hook**（配合 AODTweaker 做一个"沉浸式体验"大礼包）
4. **应用白名单**（选择哪些 app 媒体走药丸，哪些保留原生→ 给用户更多掌控感）
5. **Now-playing 联动**（如果装了 Now Playing 识别歌曲，让药丸显示"已识别"图标）

---

## 13. 开工前 Checklist（读完这份文档后这一个单子就够了）

读到这里，这个项目的蓝图已经完整了。把以下清单打勾之后就可以开工了：

**预开工**：
- [ ] 在 GitHub 创建空仓库，MIT 协议，添加 .gitignore (Android)
- [ ] LSPosed 已经在手机上启用了、MediaPill 模块作用域待创建
- [ ] 确认小米 12 在 LSPosed 中的 SystemUI 包名确实是 `com.android.systemui`（已通过 adb 确认）
- [ ] 确认 `adb logcat -s Xposed` 能在手机上抓到 Xposed 的 hook 日志

**阶段 0 — 脚手架（1 天）**：
- [ ] 复制 AODTweaker 的 Gradle 骨架
- [ ] 把 `build.gradle.kts` 的 compileSdk / targetSdk 调到 36
- [ ] 把 libxposed 依赖调到 API 102
- [ ] 后台 App（配置 UI）第一个页面能启动、能开关模块
- [ ] 在 SystemUI 进程打印一条 logcat 确认 Xposed 接入了

**阶段 1 — 探测+隐藏（最耗时，最小里程碑就是"能看到空白"）**：
- [ ] 反射 `com.android.systemui.media.controls.ui.controller.KeyguardMediaController` 的所有打印签名
- [ ] hook `MediaHost` 构造方法，logcat 打印所有实例的 class 和 parent view
- [ ] 成功让锁屏不再有原生媒体卡（顶部没留白 = 满血成功）

**之后**：每个阶段完成后在 GitHub 发一个 dev release 并 git tag。

