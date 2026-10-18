# 夜莺播放器 · Android

> 共同工作区：[MediaSuite](../README.md) · 共同开发与前端规范：[AGENTS.md](../AGENTS.md)。

面向助眠场景的 Android 本地媒体播放器，支持 MP4、MP3、WAV，外挂 WebVTT 字幕和 LRC 歌词。

名称保持「夜莺播放器 · Nightingale」，使用与 Windows 版一致的月牙与青鸟图标，与媒体制作工具「织声 · Soundweaver」配套。自适应图标和安全区说明见 [品牌资源](assets/branding/README.md)。

当前源码版本由 `app/build.gradle.kts` 定义：`versionName = "4.0"`、`versionCode = 11`，最低 Android 8.0（API 26）。内部包名/applicationId `com.myp.sleepplayer`、项目目录 `NightingalePlayer`、已有偏好文件和模拟器媒体目录 `SleepVideoPlayer` 保持兼容，不随对外名称更改。已有 APK 可能来自较早的构建，不能仅凭文件名认定与当前源码一致。

## 已实现

- 精确 seek：使用 Media3 `SeekParameters.EXACT`，低帧率视频会从前一关键帧解码到目标位置；在视频画面上左右滑动可快进或回退，距离越长跳转越远。
- 自动字幕：同目录匹配 `video.vtt`、`video.wav.vtt`、`video.zh.vtt` 等文件；同名 `.lrc` 歌词（含 `[offset:]` 与一行多时间戳）会自动转成 WebVTT 使用。
- 字幕定位：可在“字幕设置”启用“有字幕时，滑动对齐字幕起始点”；横向拖动会将目标位置吸附到当前媒体匹配到的 VTT/LRC cue 起点，关闭时保持连续 seek。
- 画面控制：播放器控制栏提供全屏和设置入口；设置菜单可调整画面适配、裁剪、拉伸、双指缩放、倍速、增益、定时和字幕，全屏时也可使用。
- 隐藏目录：应用内目录浏览器可显示点号开头的目录；需由用户主动授予“所有文件访问”。
- 音频控制：播放器控制栏的独立喇叭图标打开竖向音量滑杆；设置菜单另支持原声、+6/+12 dB 增益和 0.5x 到 2x 倍速。
- 定时关闭和后台播放：基于 Media3 `MediaSessionService` 的前台媒体服务；设置菜单中的定时面板显示实时剩余时间，并支持快捷时长和自定义分钟数。
- 后台字幕：授权悬浮窗后，退到后台播放时显示当前 VTT 字幕；回到播放器后自动隐藏。
- 字幕样式：字幕设置中可实时预览并调整字号、颜色、背景透明度和底部距离。
- 播放恢复：退到后台或重新进入应用时恢复媒体、位置和播放状态。
- 播放列表：支持跨目录混排，切换目录和重启后保留；有列表时显示在播放屏幕下方。支持整卡拖动排序、长按移除、双击目录媒体追加，以及本地歌单的新建、打开、重命名、删除与自动保存；独立操作行提供循环开关和可撤销的整表清空。循环由后台播放服务执行，睡眠定时到期仍会暂停。
- MP4 预览：媒体库将 MP4 与 MP3/WAV 分开展示，并在可见 MP4 条目旁显示视频缩略图；重复预览复用缓存，离屏不解码。

## 代码架构

应用按职责拆分为同一 Android app module 下的几个 package；暂不增加 Gradle module，以控制依赖和构建复杂度。

```text
com.myp.sleepplayer
├── MainActivity.kt             页面组装、生命周期、用户交互和 Android UI
├── PlaybackQueueView.kt        主页播放列表、整卡拖动排序与长按移除
├── SavedPlaylistsDialog.kt     我的歌单列表、新建入口、重命名与删除
├── VideoThumbnailLoader.kt     可见缩略图解码、请求合并与有界内存缓存
├── PlaybackService.kt          MediaSession/ExoPlayer 生命周期、后台播放与服务命令
├── media/
│   ├── MediaModels.kt          媒体/字幕数据模型和文件类型
│   ├── MediaItemMapper.kt      应用媒体模型到 Media3 MediaItem 的适配
│   ├── MediaScanner.kt         File 与 SAF 目录扫描、字幕匹配、LRC 转 VTT 与缓存
│   └── SubtitleSupport.kt      LRC 时间戳解析和 WebVTT 时间格式化
└── playback/
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

## 播放列表

设置列表后，播放屏幕下方依次显示“播放列表”和“当前目录内容”，MP4 和音频作为当前目录的子分组；未设置列表时，直接显示 MP4、音频分组，不显示这两层总标题或歌单操作行。预览仍可折叠收起，目录区域不再常驻显示操作提示和目录路径。

“播放列表”标题下方操作行的“循环”开关独立控制是否循环，开启后，列表只有一项时重复播放这一项，多项时按编号依次播放并回到第一项；关闭后，按列表顺序播放到末项结束。切换循环选项立即生效并记忆，不改变当前媒体、进度或播放/暂停状态。播放器设置菜单不再放置播放列表和循环入口。

主页列表和添加媒体窗口复用媒体库现有的 MP4 缩略图与字幕标记，音频显示文件名和格式。直接按住主页卡片任意位置上下拖动即可排序；长按不动进入移除模式，所有卡片右上角一起显示 ×，可连续点击剔除条目。点任意卡片中间收起全部 ×，恢复播放和拖动操作。排序、移除立即应用并保存，无需进入编辑窗口或另点保存。移除只影响列表，不删除媒体文件；当前媒体仍在应用后的播放队列中时保留进度与播放/暂停状态，不在新队列时停在第一项，等待手动播放。

单击目录媒体卡片直接播放；已有播放列表时，双击卡片只把媒体追加到列表末尾，不中断当前播放。目录播放队列与自定义播放列表分别保存，编辑播放列表不会替换正在播放的目录队列；点击播放列表中的卡片才切回列表播放。“添加媒体”位于播放列表展开内容内、媒体卡片上方；尚无列表时，从顶部“歌单”新建或打开歌单。选择窗口只显示本目录中尚未加入列表的媒体，确认后追加，取消不改动列表。

播放列表在整个应用中保存，允许把不同目录的媒体混合排列。切换目录只更新“当前目录内容”，保留列表和正在播放的队列；应用重启也会恢复列表及其字幕信息。

顶部“歌单”与“目录”“字幕”同排，统一提供新建和读取入口。打开的“我的歌单”窗口列出本机已保存歌单的名称、媒体项数和当前标记，点选即可打开；每行的 ⋮ 可重命名或删除。播放列表操作行的 ⋮ 提供“保存为歌单”或“另存为新歌单”。打开或创建歌单后，标题直接显示“歌单名 · X 项”，媒体增删、排序和循环设置自动更新当前歌单。歌单使用独立标识，同名歌单也不会意外覆盖；旧版命名歌单会保留并自动迁移。空歌单可以继续添加媒体。

标题将歌单名与项数合为一行，长名称中间省略，折叠箭头位于最右侧；未命名时显示“播放列表 · X 项”。循环、更多操作和清空放在下方独立操作行。“添加媒体”与拖动、长按提示共用一行，使用轻量图标与文字按钮，随播放列表一起收起；当前目录不再显示歌单操作入口。× 清空当前播放列表，并临时显示“撤销”；在主界面继续完成 3 次触摸操作或等待 10 秒后，提示自动淡出。清空保留当前媒体和进度，解除已保存歌单的关联，不清空或删除该歌单。新建、打开或恢复列表会结束上一次清空的撤销提示。删除歌单需确认，只移除歌单记录，不删除媒体文件；删除当前歌单时，当前队列仍可使用。

列表调整只更新播放列表区域，保留当前目录卡片。折叠分组首次展开时才创建卡片；视频缩略图只为可见项请求，同一 URI 合并解码并复用有内存上限的缓存，Android 8.1 及以上直接提取缩放帧。滚动与布局事件合并检查可见性，避免每帧遍历所有预览。未变化的队列快照跳过重复 JSON 编码和写入。

## 模拟器调试

环境默认使用 `D:\tools\android-sdk` 和 AVD `northward_api36`，Java 需预先可用。建议只保留一个正在运行的模拟器：安装/启动脚本选择枚举到的最后一个 `emulator-*`，同步脚本选择第一个；它们不保证在多模拟器情况下选择相同设备。

1. 双击 `run_debug.bat`，启动模拟器、构建、安装并打开应用。
2. 把媒体文件放到 `test-media`。
3. 双击 `sync_media.bat`，镜像到模拟器的 `/sdcard/Movies/SleepVideoPlayer`。
4. 应用中点击“选择目录”，授权 `Movies/SleepVideoPlayer`。

`sync_media.bat` 会先上传整个 `test-media` 目录（不限文件扩展名），再把模拟器原目录移入 `/sdcard/Movies/.SleepVideoPlayer-backups/<时间戳>` 并切换到新目录。已存在但为空的 `test-media` 也会把目标替换为空目录；不存在时只创建本机目录并退出。此操作不是增量添加，运行前应确认本机目录内容。

普通目录可以继续使用系统目录选择器。要打开 `.movie` 等点号开头的隐藏目录，点击“选择目录” → “浏览全部目录（含隐藏）”，按系统提示允许“所有文件访问”，再在应用内选择目录。该权限只用于读取用户主动选择的媒体目录。

## 后台字幕

应用中点击“字幕”，打开“退到后台时显示悬浮字幕”，再点击“授权悬浮字幕显示”并允许“显示在其他应用上层”。保存后，播放带 VTT 的媒体并退到后台；只有当前 cue 有文字时才会显示悬浮字幕。字幕设置也会同步作用于前台视频字幕。

`run_debug.bat`、`install_debug.bat` 和 `sync_media.bat` 都只选择 `emulator-*` 设备，不会选中真机。`install_debug.bat` 不编译，也不启动模拟器；它会覆盖安装已有 Debug APK，随后强停并启动应用，可能中断当前播放。三个批处理脚本结束时均会等待按键。完整操作说明见 [调试说明](调试说明.md)。

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
```

它们会编译相关测试类并执行 JVM 检查，仍会写构建与依赖缓存。播放列表检查覆盖选曲、排序、缺失文件、无效移动和进度恢复；通过不代表设备上的播放、手势、后台服务或权限交互已经验收。

构建发布包：

```powershell
.\gradlew.bat --console=plain :app:assembleRelease
```

发布构建使用上述同一环境，沿用本机 Android debug 签名，APK 输出在 `app\build\outputs\apk\release\app-release.apk`。使用同一 debug keystore 且版本号不降低时，可以覆盖安装先前的 Debug 版本；换机器或旧 APK 版本不同可能不满足这一条件。构建不会安装或发布 APK。正式上架时应替换为专用的私有 release keystore。

## 数据与交互副作用

应用会在自身私有存储中保存目录授权、播放位置和状态、音量/倍速/增益、画面模式、字幕与定时偏好；LRC 转换后的 WebVTT 写入应用缓存。普通目录通过系统选择器授权，隐藏目录入口会请求“所有文件访问”，悬浮字幕会请求“显示在其他应用上层”。打开媒体会启动播放服务；睡眠定时到期会暂停并停止服务。安装与同步操作会写入模拟器，不能当作只读验证。
