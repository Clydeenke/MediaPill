# Changelog

## [0.1.0] - 2026-08-05

### Added
- 项目骨架：libxposed API 102 + Kotlin 2.4.10 + AGP 9.3.0 + miuix-kmp
- `PillHookEntry` SystemUI 注入入口（阶段 1：完整探测 8 个目标类 + 构造方法 + 字段 + Compose 可用性检查）
- `App` / `ConfigService` 跨进程 RemotePreferences 基础设施（基于 libxposed-service）
- `MainActivity` miuix-kmp Compose 配置 UI（主开关）
- 可行性评估文档 `docs/MEDIAPILL_FEASIBILITY.md`
- 应用图标（自适应图标 + monochrome）
