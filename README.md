# FloatCam 浮窗相机

悬浮摄像头与水印图层 —— 游戏直播/录屏场景的摄像头悬浮窗工具。

摄像头与水印是两个**独立 WindowManager 窗口**，可并行、分别开关。App 只负责在屏幕上显示可配置的悬浮窗，推流、编码、屏幕捕获交给直播软件，直播软件通过系统屏幕捕获自然读到本 App 的悬浮窗。

## 功能特性

- 📷 **摄像头悬浮窗**：CameraX 预览，悬浮在任意应用之上
- 🖼️ **水印悬浮窗**：透明 PNG 水印图层，独立窗口独立开关
- 🎚️ **独立控制**：摄像头、水印可分别开/关，互不联动
- ✋ **两态运行**：编辑态（拖动/缩放/调透明度）+ 锁定态（穿透，实测为准）
- 🔄 **动态前台服务类型**：有相机用 `camera`，仅水印切 `specialUse`（API 34+），双隐停止
- 🔒 **隐私安全**：不设 FLAG_SECURE、不申请 MediaProjection、不依赖无障碍、不静默自启

## 技术栈

| 领域 | 选型 |
| --- | --- |
| UI | 单 Activity + Jetpack Compose |
| 相机 | CameraX + PreviewView |
| 服务 | LifecycleService + 前台服务（动态类型 camera \| specialUse） |
| 持久化 | DataStore（Preferences，800ms 防抖 + 5s 最大防抖窗口） |
| 图片选择 | Photo Picker（PickVisualMedia，API 26 回退） |
| 窗口 | WindowManager + TYPE_APPLICATION_OVERLAY |
| 最低版本 | API 26 |

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/`。CI 通过 GitHub Actions 自动编译，Artifact 见 Actions 页面。

## 目录结构

```
app/                        # 正式工程（Kotlin + Compose）
  src/main/java/com/cuiyisrama/floatcam/
    MainActivity.kt         # 服务探测、权限、Compose 首页
    service/                # OverlayService + OverlayBinder（唯一状态源）
step0/                      # Step 0 验证探针（纯 Java，测完即弃）
```

## 版本

- v0.1.0：正式工程骨架（Compose 首页 + 前台服务动态类型切换 + 权限链路），CI 编译通过
- Step 0 验证（探针）：在目标 ROM 实测不可信触摸阻断、FGS 类型切换、锁屏恢复，结论决定后续功能验收标准

## 目标设备

- 主目标机：vivo V2425A / Android 17 (SDK 37)
- 兼容：Android 12+（不可信触摸机制），API 26+ 可安装

## 许可

MIT License

---

架构蓝图与 Step 0 实测矩阵见项目文档（`docs/` 规划中）。