# 夜莺播放器

面向助眠场景的 Android 本地媒体播放器，支持 MP4、MP3、WAV，外挂 WebVTT 字幕和 LRC 歌词。

## 已实现

- 精确 seek：使用 Media3 `SeekParameters.EXACT`，低帧率视频会从前一关键帧解码到目标位置；在视频画面上左右滑动可快进或回退，距离越长跳转越远。
- 自动字幕：同目录匹配 `video.vtt`、`video.wav.vtt`、`video.zh.vtt` 等文件；同名 `.lrc` 歌词（含 `[offset:]` 与一行多时间戳）会自动转成 WebVTT 使用。
- 画面控制：播放器控制栏提供全屏和设置入口；设置菜单可调整画面适配、裁剪、拉伸、双指缩放、倍速、增益、定时和字幕，全屏时也可使用。
- 隐藏目录：应用内目录浏览器可显示点号开头的目录；需由用户主动授予“所有文件访问”。
- 音频控制：播放器控制栏的独立喇叭图标打开竖向音量滑杆；设置菜单另支持原声、+6/+12 dB 增益和 0.5x 到 2x 倍速。
- 定时关闭和后台播放：基于 Media3 `MediaSessionService` 的前台媒体服务；设置菜单中的定时面板显示实时剩余时间，并支持快捷时长和自定义分钟数。
- 后台字幕：授权悬浮窗后，退到后台播放时显示当前 VTT 字幕；回到播放器后自动隐藏。
- 字幕样式：字幕设置中可实时预览并调整字号、颜色、背景透明度和底部距离。
- 播放恢复：退到后台或重新进入应用时恢复媒体、位置和播放状态。
- MP4 预览：媒体库将 MP4 与 MP3/WAV 分开展示，并在每个 MP4 条目旁直接显示视频首帧缩略图。

## 代码架构

应用按职责拆分为同一 Android app module 下的几个 package；暂不增加 Gradle module，以控制依赖和构建复杂度。

```text
com.myp.sleepplayer
├── MainActivity.kt             页面组装、生命周期、用户交互和 Android UI
├── PlaybackService.kt          MediaSession/ExoPlayer 生命周期、后台播放与服务命令
├── media/
│   ├── MediaModels.kt          媒体/字幕数据模型和文件类型
│   ├── MediaItemMapper.kt      应用媒体模型到 Media3 MediaItem 的适配
│   ├── MediaScanner.kt         File 与 SAF 目录扫描、字幕匹配、LRC 转 VTT 与缓存
│   └── SubtitleSupport.kt      LRC 时间戳解析和 WebVTT 时间格式化
└── playback/
    ├── PlaybackCoordinator.kt  播放列表装载、恢复决策和条目播放
    ├── PlaybackStateStore.kt   播放位置快照的 SharedPreferences 读写
    ├── PlaybackControlStore.kt 音量、倍速、增益和画面模式偏好
    ├── SleepTimerStore.kt      定时截止时间偏好
    └── SubtitlePreferencesStore.kt 字幕样式与悬浮显示偏好
```

`MainActivity` 仍负责页面、Android 生命周期、播放器手势与控件交互，并协调扫描结果展示。媒体扫描、字幕处理和播放列表/恢复逻辑已移出 Activity：`MediaScanner` 将普通文件目录与系统目录授权（SAF）目录统一转换为 `MediaEntry`；`MediaItemMapper` 负责把应用媒体模型映射到 Media3 `MediaItem`。`PlaybackCoordinator` 集中管理播放列表和恢复决策，`PlaybackStateStore` 保留原有播放位置键和值；`PlaybackControlStore`、`SleepTimerStore` 和 `SubtitlePreferencesStore` 分别集中管理现有偏好文件中的控制、定时和字幕设置。`PlaybackService` 继续拥有实际播放器和后台播放生命周期。

依赖方向以 UI 调用媒体和播放能力为主：

```text
MainActivity ──> media models / MediaScanner
MainActivity ──> PlaybackCoordinator ──> PlaybackStateStore
PlaybackCoordinator ──> media models
MainActivity / PlaybackService ──> preference stores
PlaybackService ──> Media3 player/session
```

服务命令仍通过现有的 `Intent` action 在 Activity 与 `PlaybackService` 之间传递；偏好 store 只负责持久化，不替代服务命令通道。Activity 通过 `PlaybackControlStore` 保存音量、静音、倍速、增益和画面模式；`PlaybackService` 通过同一 store 在处理增益命令时保存增益。定时和字幕设置由 `PlaybackService` 写入对应 store，Activity 读取这些值用于显示。后续扩展优先将纯字幕时间轴与 cue 查找放入 `media`，将 seek 策略放入 `playback`，UI 只发出操作并显示结果；只有在职责和依赖稳定后再考虑继续拆分手势、设置弹窗或 Service 管理器。当前分层不改变支持的媒体格式、扫描顺序、字幕匹配规则、LRC 转换缓存、播放恢复或现有控制行为。

字幕解析自检可运行 `gradlew.bat :app:verifySubtitleSupport`，覆盖 LRC offset、多时间戳、重复起点、末尾 cue 时长及 WebVTT 输出。

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

构建发布包：

```powershell
$env:GRADLE_USER_HOME = "D:\tools\gradle-user-home"
.\gradlew.bat assembleRelease
```

当前发布构建沿用本机 Android debug 签名，APK 输出在 `app\build\outputs\apk\release\app-release.apk`，可直接安装并覆盖 `run_debug.bat` 安装的版本。正式上架时应替换为专用的私有 release keystore。
