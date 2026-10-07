# 夜莺播放器

面向助眠场景的 Android 本地媒体播放器，支持 MP4、MP3、WAV 和外挂 WebVTT 字幕。

## 已实现

- 精确 seek：使用 Media3 `SeekParameters.EXACT`，低帧率视频会从前一关键帧解码到目标位置；在视频画面上左右滑动可快进或回退，距离越长跳转越远。
- 自动字幕：同目录匹配 `video.vtt`、`video.wav.vtt`、`video.zh.vtt` 等文件。
- 画面控制：播放器控制栏提供全屏和设置入口；设置菜单可调整画面适配、裁剪、拉伸、双指缩放、倍速、增益、定时和字幕，全屏时也可使用。
- 隐藏目录：应用内目录浏览器可显示点号开头的目录；需由用户主动授予“所有文件访问”。
- 音频控制：播放器控制栏的独立喇叭图标打开竖向音量滑杆；设置菜单另支持原声、+6/+12 dB 增益和 0.5x 到 2x 倍速。
- 定时关闭和后台播放：基于 Media3 `MediaSessionService` 的前台媒体服务；设置菜单中的定时面板显示实时剩余时间，并支持快捷时长和自定义分钟数。
- 后台字幕：授权悬浮窗后，退到后台播放时显示当前 VTT 字幕；回到播放器后自动隐藏。
- 字幕样式：字幕设置中可实时预览并调整字号、颜色、背景透明度和底部距离。
- 播放恢复：退到后台或重新进入应用时恢复媒体、位置和播放状态。
- MP4 预览：媒体库将 MP4 与 MP3/WAV 分开展示，并在每个 MP4 条目旁直接显示视频首帧缩略图。

## 模拟器调试

环境默认使用 `D:\tools\android-sdk` 和 AVD `northward_api36`。

1. 双击 `run_debug.bat`，启动模拟器、构建、安装并打开应用。
2. 把媒体文件放到 `test-media`。
3. 双击 `sync_media.bat`，镜像到模拟器的 `/sdcard/Movies/SleepVideoPlayer`。
4. 应用中点击“选择目录”，授权 `Movies/SleepVideoPlayer`。

普通目录可以继续使用系统目录选择器。要打开 `.movie` 等点号开头的隐藏目录，点击“选择目录” → “浏览全部目录（含隐藏）”，按系统提示允许“所有文件访问”，再在应用内选择目录。该权限只用于读取用户主动选择的媒体目录。

## 后台字幕

应用中点击“字幕”，打开“退到后台时显示悬浮字幕”，再点击“授权悬浮字幕显示”并允许“显示在其他应用上层”。保存后，播放带 VTT 的媒体并退到后台；只有当前 cue 有文字时才会显示悬浮字幕。字幕设置也会同步作用于前台视频字幕。

`sync_media.bat` 只选择 `emulator-*` 设备，不会写入真机。只安装已有 APK 可使用 `install_debug.bat`。

## 构建

```powershell
$env:GRADLE_USER_HOME = "D:\tools\gradle-user-home"
.\gradlew.bat assembleDebug
```

APK 输出在 `app\build\outputs\apk\debug\app-debug.apk`。
