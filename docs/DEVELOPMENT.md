# 开发与构建

[返回首页](../README.md) · [使用指南](USAGE.md) · [调试说明](../调试说明.md)

所有命令均从项目根目录运行。

## 项目与版本

当前源码版本由 `app/build.gradle.kts` 定义：`versionName = "0.5"`、`versionCode = 13`，最低 Android 8.0（API 26）。内部包名/applicationId `com.myp.sleepplayer`、项目目录 `NightingalePlayer`、已有偏好文件和模拟器媒体目录 `SleepVideoPlayer` 保持兼容，不随对外名称更改。已有 APK 可能来自较早的构建，不能仅凭文件名认定与当前源码一致。

技术栈为 Kotlin、Android Views 与 Media3（1.5.1）。

## 播放实现与性能

- 精确 seek：使用 Media3 `SeekParameters.EXACT`，低帧率视频会从前一关键帧解码到目标位置；在视频画面上左右滑动可快进或回退，距离越长跳转越远。
- 本地跳转：起播/跳转后的缓冲门槛设为 100 ms、重新缓冲门槛为 250 ms，并保留最近 15 秒及其前一关键帧的样本，减少短距离回退的重复读取；短暂等待不闪加载图标，持续等待超过 400 ms 才显示。继续保留精确落点和字幕对齐；关键帧间隔较长的视频仍需要补解码，实际恢复速度取决于视频编码和设备。
- 跳转优化实验：H.264 精确跳转时，仅跳过目标至少一秒之前、可确认不会被其他画面引用的普通非参考帧；保留参考帧、目标附近、片尾、加密与无法确认的样本，其他编码沿用 Media3 原有路径。正在播放且选中音轨时，可在 Media3 有界等待窗口内先恢复音频，让视频追上同一播放时钟；暂停跳转仍等待目标画面并保持暂停。压缩数据预读目标约一分钟，使用 64 MiB 加载目标优先限制继续读取，实际预读时长随码率和保留的回退数据变化。这些处理不重编码、不修改原媒体，也不保证全部 MP4 零等待。
- MP4 预览：媒体库将 MP4 与 MP3/WAV 分开展示，并在可见 MP4 条目旁显示视频缩略图；重复预览复用缓存，离屏不解码。

歌单只在快照变化时编码和写入，调整列表时局部更新页面。预览按可见项加载，合并同一 URI 的请求并使用有界缓存；Android 8.1 及以上直接提取缩放帧。

## 代码架构

应用按职责拆分为同一 Android app module 下的几个 package；暂不增加 Gradle module，以控制依赖和构建复杂度。

```text
com.myp.sleepplayer
├── MainActivity.kt             页面组装、生命周期、用户交互和 Android UI
├── PlaybackQueueView.kt        主页播放列表、整卡拖动排序与长按移除
├── FullscreenEpisodesPopup.kt  全屏右侧选集、复用预览与当前项标记
├── SavedPlaylistsDialog.kt     我的歌单列表、新建入口、重命名与删除
├── VideoThumbnailLoader.kt     可见缩略图解码、请求合并与有界内存缓存
├── PlaybackService.kt          MediaSession/ExoPlayer 生命周期、后台播放与服务命令
├── media/
│   ├── MediaModels.kt          媒体/字幕数据模型和文件类型
│   ├── MediaItemMapper.kt      应用媒体模型到 Media3 MediaItem 的适配
│   ├── MediaScanner.kt         File 与 SAF 目录扫描、字幕匹配、LRC 转 VTT 与缓存
│   └── SubtitleSupport.kt      LRC 时间戳解析和 WebVTT 时间格式化
└── playback/
    ├── LocalRenderersFactory.kt 本地跳转渲染器、非参考帧跳过与音频先恢复
    ├── H264SampleDependencies.kt 保守识别 Annex B 非参考画面
    ├── PlaybackCoordinator.kt  播放列表装载、恢复决策和条目播放
    ├── PlaybackQueue.kt        循环模式、选曲/顺序与恢复决策
    ├── PlaybackQueueStore.kt   循环模式、跨目录列表、命名歌单和目录播放队列的持久化
    ├── PlaybackStateStore.kt   播放位置快照的 SharedPreferences 读写
    ├── PlaybackControlStore.kt 音量、倍速、增益和画面模式偏好
    ├── SleepTimerStore.kt      定时截止时间偏好
    ├── SubtitlePreferencesStore.kt 字幕样式、悬浮显示和字幕定位偏好
    └── SubtitleSeekSnapper.kt  横向 seek 目标吸附到 cue 起点
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

字幕与定位自检覆盖 LRC offset、多时间戳、VTT cue 起点、重复起点、末尾 cue 时长、WebVTT 输出和最近字幕起点吸附；环境与命令见下方“构建与自动检查”。

## 模拟器调试

环境默认使用 `D:\tools\android-sdk` 和 AVD `northward_api36`，Java 需预先可用。建议只保留一个正在运行的模拟器：安装/启动脚本选择枚举到的最后一个 `emulator-*`，同步脚本选择第一个；它们不保证在多模拟器情况下选择相同设备。

1. 双击 `run_debug.bat`，启动模拟器、构建、安装并打开应用。
2. 把媒体文件放到 `test-media`。
3. 双击 `sync_media.bat`，镜像到模拟器的 `/sdcard/Movies/SleepVideoPlayer`。
4. 应用中点击“选择目录”，授权 `Movies/SleepVideoPlayer`。

`sync_media.bat` 会先上传整个 `test-media` 目录（不限文件扩展名），再把模拟器原目录移入 `/sdcard/Movies/.SleepVideoPlayer-backups/<时间戳>` 并切换到新目录。已存在但为空的 `test-media` 也会把目标替换为空目录；不存在时只创建本机目录并退出。此操作不是增量添加，运行前应确认本机目录内容。

普通目录可以继续使用系统目录选择器。要打开 `.movie` 等点号开头的隐藏目录，点击“选择目录” → “浏览全部目录（含隐藏）”，按系统提示允许“所有文件访问”，再在应用内选择目录。该权限只用于读取用户主动选择的媒体目录。

`run_debug.bat`、`install_debug.bat` 和 `sync_media.bat` 都只选择 `emulator-*` 设备，不会选中真机。`install_debug.bat` 不编译，也不启动模拟器；它会覆盖安装已有 Debug APK，随后强停并启动应用，可能中断当前播放。三个批处理脚本结束时均会等待按键。完整操作说明见 [调试说明](../调试说明.md)。

## 构建与自动检查

需要 JDK 17、Android SDK Platform 36，以及 Gradle Wrapper 使用的构建依赖。以下命令从项目目录执行；本机工具路径如下，迁移到其他机器时按实际安装位置调整。Gradle 首次运行可能下载 Wrapper 和缺失依赖。

```powershell
$env:JAVA_HOME = "D:\tools\jdk17\jdk-17.0.20+8"
$env:ANDROID_HOME = "D:\tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "D:\tools\gradle-user-home"
.\gradlew.bat --console=plain :app:assembleDebug
```

APK 输出在 `app\build\outputs\apk\debug\app-debug.apk`。这是只构建入口，不会启动模拟器、安装 APK 或打开应用；会更新项目的 `.gradle` / `build` / `app/build` 和指定的 Gradle 缓存。

使用上述同一环境运行不需要设备的字幕与定位检查：

```powershell
.\gradlew.bat --console=plain :app:verifySubtitleSupport
.\gradlew.bat --console=plain :app:verifyPlaybackQueue
.\gradlew.bat --console=plain :app:verifyH264Dependencies
```

它们会编译相关测试类并执行 JVM 检查，仍会写构建与依赖缓存。播放列表检查覆盖选曲、排序、缺失文件、无效移动和进度恢复；H.264 检查覆盖参考帧与多 slice 判断、未知与截断 NAL 回退、起始码和缓冲区位置保持。通过不代表设备上的播放、手势、后台服务或权限交互已经验收。

构建发布包：

```powershell
.\gradlew.bat --console=plain :app:assembleRelease
```

发布构建使用上述同一环境，沿用本机 Android debug 签名，APK 输出在 `app\build\outputs\apk\release\app-release.apk`。使用同一 debug keystore 且版本号不降低时，可以覆盖安装先前的 Debug 版本；换机器或旧 APK 版本不同可能不满足这一条件。构建不会安装或发布 APK。正式上架时应替换为专用的私有 release keystore。

## 数据与交互副作用

应用会在自身私有存储中保存目录授权、播放位置和状态、音量/倍速/增益、画面模式、字幕与定时偏好；LRC 转换后的 WebVTT 写入应用缓存。普通目录通过系统选择器授权，隐藏目录入口会请求“所有文件访问”，悬浮字幕会请求“显示在其他应用上层”。打开媒体会启动播放服务；睡眠定时到期会暂停并停止服务。安装与同步操作会写入模拟器，不能当作只读验证。

## 共同工作区

[MediaSuite](../../README.md) · [共同开发规范](../../AGENTS.md) · [品牌资源](../assets/branding/README.md)
