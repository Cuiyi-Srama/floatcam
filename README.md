# FloatCam 浮窗相机

悬浮摄像头与水印图层。摄像头和水印是两个独立 WindowManager 窗口，可并行、分别开关。

- 包名：`com.cuiyisrama.floatcam`（正式）
- 目标机：vivo V2425A / Android 17 (SDK 37)
- 编译链路：GitHub Actions 云端编译

## 目录结构

- `step0/` — Step 0 验证探针（纯 Java，包名 `com.cuiyisrama.step0probe`），用于实测不可信触摸阻断、FGS 类型切换、锁屏恢复等前置验证。测完即弃，不进入正式工程。

## Step 0 验证 APK

构建：`./gradlew :step0:assembleRelease`
产物：`step0/build/outputs/apk/release/`

功能：悬浮窗显示 Camera2 后置预览，MainActivity 内可循环切换窗口 alpha（1.0 / 0.8 / 0.75 / 0.7），用于在目标 ROM 上实测后台场景下高不透明度的触摸阻断行为。

架构蓝图见 `/sdcard/Download/Operit/cleanOnExit/pasted_text_9219963240600061490.txt`（第 1 版，含已确认的 6.3 动态类型切换、G 组 specialUse 存活验证等修改）。