# 夜莺播放器

面向助眠场景的 Android 本地媒体播放器，支持 MP4、MP3、WAV 和外挂 WebVTT 字幕。

## 已实现

- 精确 seek：使用 Media3 `SeekParameters.EXACT`，低帧率视频会从前一关键帧解码到目标位置。
- 自动字幕：同目录匹配 `video.vtt`、`video.wav.vtt`、`video.zh.vtt` 等文件。
- 画面控制：适配、裁剪放大、拉伸、双指缩放。
- 音频控制：播放器音量、系统媒体音量、静音、+6/+12 dB 增益、0.5x 到 2x 倍速。
- 定时关闭和后台播放：基于 Media3 `MediaSessionService` 的前台媒体服务。
- 播放恢复：退到后台或重新进入应用时恢复媒体、位置和播放状态。

## 模拟器调试

环境默认使用 `D:\tools\android-sdk` 和 AVD `northward_api36`。

1. 双击 `run_debug.bat`，启动模拟器、构建、安装并打开应用。
2. 把媒体文件放到 `test-media`。
3. 双击 `sync_media.bat`，镜像到模拟器的 `/sdcard/Movies/SleepVideoPlayer`。
4. 应用中点击“选择目录”，授权 `Movies/SleepVideoPlayer`。

`sync_media.bat` 只选择 `emulator-*` 设备，不会写入真机。只安装已有 APK 可使用 `install_debug.bat`。

## 构建

```powershell
$env:GRADLE_USER_HOME = "D:\tools\gradle-user-home"
.\gradlew.bat assembleDebug
```

APK 输出在 `app\build\outputs\apk\debug\app-debug.apk`。
