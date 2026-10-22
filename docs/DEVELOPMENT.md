# 开发与构建

[返回首页](../README.md) · [使用指南](USAGE.md) · [调试说明](../调试说明.md)

所有命令均从项目根目录运行。

## 项目与版本

当前源码版本以 `app/build.gradle.kts` 的 `versionName`、`versionCode` 为准，最低 Android 8.0（API 26）。内部包名/applicationId `com.myp.sleepplayer`、项目目录 `NightingalePlayer`、已有偏好文件和模拟器媒体目录 `SleepVideoPlayer` 保持兼容，不随对外名称更改。已有 APK 可能来自较早的构建，不能仅凭文件名认定与当前源码一致。

技术栈为 Kotlin、Android Views 与 Media3（1.5.1）。

## 播放实现与性能

- 视频与页面刷新率：在创建 ExoPlayer 时设置 `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`，关闭 Media3 在 Android 11 及以上对视频 Surface 发出的帧率请求。低帧率 MP4 会触发系统对应用帧率的覆盖，使共用窗口内的媒体库滚动也掉帧；关闭请求后，屏幕刷新率交由 UI 和系统决定，视频解码、帧释放时序与 VSync 保持原实现。用户已在 OnePlus Android 16 实机确认低帧率视频触发该问题，暂停或切换音频文件不出现；修复后页面滚动恢复流畅。此问题不是字幕逐帧重排。
- 精确 seek：使用 Media3 `SeekParameters.EXACT`，低帧率视频会从前一关键帧解码到目标位置；在视频画面上左右滑动可快进或回退，距离越长跳转越远。
- 本地跳转：起播/跳转后的缓冲门槛设为 100 ms、重新缓冲门槛为 250 ms，并保留最近 15 秒及其前一关键帧的样本，减少短距离回退的重复读取；短暂等待不闪加载图标，持续等待超过 400 ms 才显示。继续保留精确落点和字幕对齐；关键帧间隔较长的视频仍需要补解码，实际恢复速度取决于视频编码和设备。
- 跳转优化实验：H.264 精确跳转时，仅跳过目标至少一秒之前、可确认不会被其他画面引用的普通非参考帧；保留参考帧、目标附近、片尾、加密与无法确认的样本，其他编码沿用 Media3 原有路径。正在播放且选中音轨时，可在 Media3 有界等待窗口内先恢复音频，让视频追上同一播放时钟；暂停跳转仍等待目标画面并保持暂停。压缩数据预读目标约一分钟，使用 64 MiB 加载目标优先限制继续读取，实际预读时长随码率和保留的回退数据变化。这些处理不重编码、不修改原媒体，也不保证全部 MP4 零等待。
- MP4 预览：媒体库将 MP4 与 MP3/WAV 分开展示，并在 MP4 条目旁显示视频缩略图；重复预览复用缓存，目录列表提前处理屏幕附近的封面。

歌单只在快照变化时编码和写入，调整列表时局部更新页面。目录媒体组保留原有外层滚动和分组交互，使用固定行高的虚拟列表：总高度由条目数计算，只创建可见区域及上下各一屏缓冲内的行，移出窗口的行移出视图树并解除预览绑定。窗口按包含列表的 ScrollView 实际可见高度计算，接近分组首尾时仍保持同一预取距离；折叠或离开目录会移除已创建行，展开或重访时按当前窗口重建。常驻媒体卡片数量只随视口大小变化，不随目录总条目数或累计浏览数增长。

目录封面在与行挂载相同的窗口内加载，预取完成的封面留在屏外行中，进入屏幕时可直接绘制。每次解码槽空闲时重新按位置排序：屏内项优先，屏外项按距离由近到远；屏内项尚在查询缓存时，也不先启动屏外解码。同一 URI 的请求合并，缓存容量与单路解码上限保持原有值。播放列表等非虚拟列表仍只加载可见项。

提帧由同 UID、未导出的 `:thumbnail_decoder` 绑定服务串行执行，保留文件 URI 与 SAF 授权目录支持。从视频轨元数据读取时长，计算中点时间，调用 `MediaExtractor.seekTo(..., SEEK_TO_PREVIOUS_SYNC)` 跳到中点前关键帧，再由 `MediaCodec` 顺序解码至首个时间戳不早于中点的画面。目标帧之前立即释放解码输出，到达目标后只进行一次缩略图尺寸的 YUV → RGB 转换和旋转；不做亮度筛选、不缓存中间画面、不调用 `MediaMetadataRetriever`。没有有效时长或到真实文件结束仍无目标帧时返回失败。

一次请求只打开一个 `MediaExtractor`，外层负责释放；每次 codec 尝试负责自己的输出和释放。输入与输出共用一个循环，只有真实文件结束才发送 EOS，没有按样本数、累计读取字节数或相对中点时间截断的逻辑。整个解码过程共用 6 秒实际耗时预算，不预先分割给不同解码器；只有解码尝试抛出异常且仍有时间时，才从中点前关键帧改用一次兼容组件。真实 EOS 和耗尽预算直接结束请求，不重复解码。进程外仍以 8 秒截止保护绑定、数据源和原生调用；特别慢的设备或超长关键帧间隔仍可能超时，不能据此判定视频损坏。

缓存读取使用独立后台线程，命中磁盘封面的项目不必等待正在运行的解码。磁盘缓存位于应用私有 cacheDir，限制为 64 MiB / 2048 张，按访问时间淘汰；缓存键包含 URI、可靠的文件大小及修改时间、目标尺寸和帧策略版本，写入前复查修订信息，JPEG 临时文件完成后重命名。无法取得可靠修订信息时跳过持久复用；损坏缓存按未命中处理。内存仍按容量淘汰，不主动 recycle 可能正在绘制的图片；进入新目录浏览会清空内存并在后台重新校验源文件身份。磁盘缓存可被系统清理，不能检测保留相同大小和修改时间的原地改写。

缓存策略键为 `bounded-frame-v7-midpoint`，旧版片头封面不会误命中。当前重构保持中点选帧规则和已有缓存，无需清除应用数据。首次生成仍需进行 seek 与解码，之后同一文件从内存或磁盘缓存快速显示。

排队、查询缓存或解码期间显示静态“加载中”；一次解码返回空结果、异常或超时后，立即显示警示图标与“预览失败”，等待重试和超时冷却时保留失败提示，实际重新提交任务时恢复“加载中”，成功后显示真实封面。失败只表示当前封面生成未成功，不代表文件无法播放，卡片点击行为保持原样。占位使用轻量 Drawable，不生成位图、不写入封面缓存。

等待解码的请求由 UI 线程持有；上一个任务完成后，重新核对订阅项是否仍在加载窗口内，再把下一项交给唯一的解码线程。滚出加载窗口、折叠或切目录会移除尚未开始的查询和解码；已经运行的请求可完成并写入磁盘，但旧目录结果不会写入新目录的内存缓存或视图。异步待写缓存最多额外持有两张位图，避免慢存储导致无界积压。切目录继续复用解码服务进程，每个文件仍需初始化 codec。

当前转换支持 8 位 `YUV_420_888` 的 SDR BT.601/709 矩阵、全/有限范围及 Android 对应的扩展颜色标记；例如合法的扩展标准 85（未指定 primaries、BT.601 matrix）按 BT.601 转换。PQ/HLG HDR、10 位/P010 和尚未实现的 BT.2020 等矩阵会明确返回失败，不回退到 retriever；加密媒体也不支持。这是本次封面解码实现的兼容范围，播放仍由 Media3 独立处理，不应将封面失败表述为源文件损坏。

实机 `preview2` 现场记录到两个 `getScaledFrameAtTime(1_000_000, OPTION_CLOSEST_SYNC, ...)` 请求各耗时约 196–200 秒，随后自行返回并恢复出图，属于系统提帧长耗时造成的队列停滞。系统日志包含 `FrameDecoder.extractInternalUsingBlockModel` 输出等待超时和后续 codec 尝试，不能据此认定永久死锁或用户媒体损坏。独立 Android 16 模拟器中，同一真实样本改用 `1s + OPTION_CLOSEST` 仍两次超过 15 秒，结束客户端进程后的后续正常取帧也短暂变慢；仅修改取帧参数不足以规避。`0s + OPTION_CLOSEST` 虽可迅速返回，但两个样本均为黑首帧，未作为最终方案交付。后续改用应用控制的 `seekTo` + MediaCodec 有界顺序解码，并在 preview7 定位到视频中点，避免从开头逐帧解码和调用 retriever。厂商系统内部旧提帧路径的长耗时来源未逐项定位；中点实现当前未做设备视觉验证。

每个请求有 8 秒截止时间，覆盖绑定、打开数据源、提帧和释放。超时先完成当前客户端请求，再终止专用解码进程，确认 Binder 死亡后重新绑定，释放应用自身的工作槽位；播放器与播放服务保留在主进程。超时 URI 冷却 5 分钟，冷却记录最多 256 项，避免相同慢请求反复拖住队列。其他失败最多自动重试三次并逐次延迟，再次访问可在冷却后重试；异常、进程死亡和关闭都会清理请求状态。实际提帧还涉及系统媒体服务，终止应用子进程是否立即取消已进入系统服务的操作尚未证实；隔离与截止时间是恢复保障，不能单独作为底层长耗时已消除的验证。

`VideoThumbnails` 日志记录 `bindDecoder`（绑定）、`extract`（打开并读取视频轨）、`start`（创建与启动 codec）、`decode`（顺序解码）和 `release`（释放）阶段中超过 500 ms 的操作；任务运行超过 5 秒会报告当前阶段。日志只包含 URI 的散列标识，不输出文件路径。`RemoteThumbnails` 记录解码进程连接、超时与退出。仅中断 Java 线程无法可靠终止原生媒体调用，因此超时恢复使用专用进程回收，并限制为一个解码进程。

Debug 包每 5 秒输出 `summary`：可见/已显示/离屏已显示数量、缓存字节数/上限/淘汰次数、排队/运行任务、解码成功/null/异常次数、最久任务阶段与年龄，以及实际帧的尺寸/格式/分配字节数。Release 包仅在启动前显式设置 `log.tag.VideoThumbnails=DEBUG` 时开启汇总。诊断用最多约 4096 个弱引用采样已解码位图，不强持图片、不主动 GC；`liveBitmaps` 是尚未被 GC 清除的样本，不能单独据此判断泄漏，超限会输出 `liveBitmapTrackingDropped`。复测应结合 `attached`、`visible`、`displayed`、`cacheEvictions` 和 `decodedBitmaps`，不能把已滑过条数等同于完成解码数。

## 代码架构

应用按职责拆分为同一 Android app module 下的几个 package；暂不增加 Gradle module，以控制依赖和构建复杂度。

```text
com.myp.sleepplayer
├── MainActivity.kt             页面组装、生命周期、用户交互和 Android UI
├── PlaybackQueueView.kt        主页播放列表、整卡拖动排序与长按移除
├── FullscreenEpisodesPopup.kt  全屏右侧选集、复用预览与当前项标记
├── SavedPlaylistsDialog.kt     我的歌单列表、新建入口、重命名与删除
├── VideoThumbnailLoader.kt     可见缩略图解码、请求合并与有界内存缓存
├── ThumbnailStatusDrawable.kt  加载中与预览失败的静态图标/文字占位
├── ThumbnailDiskCache.kt       后台源修订校验、封面持久复用与容量淘汰
├── ThumbnailDecodeTask.kt      缩略图任务的成功、异常与取消完成回调
├── RemoteThumbnailDecoder.kt   绑定解码服务、截止时间与进程恢复
├── ThumbnailDecoderService.kt  专用进程中的原生提帧与释放
├── BoundedVideoFrameDecoder.kt 从视频中点前关键帧顺序解码至目标帧
├── ThumbnailMidpoint.kt        视频中点时间与目标 PTS 判断
├── VirtualMediaListView.kt     目录媒体组固定行高与可见行挂载/移除
├── ThumbnailPrefetchWindow.kt  行挂载与封面预取共用的范围及距离计算
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

前台字幕与后台悬浮字幕使用透明背景，保留描边或阴影；字幕设置移除背景透明度控件，已有偏好文件和其他设置键保持兼容，旧背景透明度值不再读取。

字幕定位模式使用系统触摸移动门槛启动横向拖动，普通快进/回退保持原有 12dp 门槛。左滑按触摸开始时所在字幕排除当前句，避免长句中途反复吸附回当前句起点；首句左滑可回到首句起点。右滑选择后续字幕，继续拖动仍按时间距离选择最近起点，回到触摸原点时取消吸附。

字幕与定位自检覆盖 LRC offset、多时间戳、VTT cue 起点、重复起点、末尾 cue 时长、WebVTT 输出、长句中途短距离左右切句、多句拖动、反向及首尾边界；环境与命令见下方“构建与自动检查”。

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
.\gradlew.bat --console=plain :app:verifyThumbnailDecode
.\gradlew.bat --console=plain :app:verifyThumbnailMidpoint
.\gradlew.bat --console=plain :app:verifyThumbnailPrefetch
```

它们会编译相关测试类并执行 JVM 检查，仍会写构建与依赖缓存。播放列表检查覆盖选曲、排序、缺失文件、无效移动和进度恢复；H.264 检查覆盖参考帧与多 slice 判断、未知与截断 NAL 回退、起始码和缓冲区位置保持；缩略图任务检查覆盖成功只完成一次、解码异常、排队取消及异常后线程继续处理下一项。通过不代表设备上的播放、手势、后台服务、预览解码或权限交互已经验收。

中点时间检查覆盖常规时长、非零起始时间、负起始时间、无效时长、溢出边界，以及目标帧前/到达/越过中点的判断；它是纯 JVM 逻辑检查，不代替实机 seek 与封面视觉验收。

预取窗口检查覆盖上下各一屏、分组首尾、列表刚在屏外、空范围、可见优先和行挂载/加载资格一致性；它不代替设备滚动或 Android 坐标转换验收。

Debug 源集提供受 `android.permission.DUMP` 保护的 `ThumbnailRecoverySmokeActivity`。在明确指定的测试模拟器中，先安装 Debug 包并提供可读 MP4，再通过 `am start` 传入 `--es goodUri <文件或内容URI> --el timeoutMs 3000`；入口会在同一 decoder 实例中先注入不返回的提帧，再解码正常文件。`ThumbnailRecoverySmoke` JSON 日志须同时显示 `stallTimedOut=true`、`recovered=true` 与 `success=true`，并结合 `RemoteThumbnails` 核对旧 PID 退出及新 PID 连接。测试 Activity 不进入 Release 包；故障 URI 仅在 debuggable 应用中生效。

构建发布包：

```powershell
.\gradlew.bat --console=plain :app:assembleRelease
```

发布构建使用上述同一环境，沿用本机 Android debug 签名，APK 输出在 `app\build\outputs\apk\release\app-release.apk`。使用同一 debug keystore 且版本号不降低时，可以覆盖安装先前的 Debug 版本；换机器或旧 APK 版本不同可能不满足这一条件。构建不会安装或发布 APK。正式上架时应替换为专用的私有 release keystore。

## 数据与交互副作用

应用会在自身私有存储中保存目录授权、播放位置和状态、音量/倍速/增益、画面模式、字幕与定时偏好；LRC 转换后的 WebVTT 写入应用缓存。普通目录通过系统选择器授权，隐藏目录入口会请求“所有文件访问”，悬浮字幕会请求“显示在其他应用上层”。打开媒体会启动播放服务；睡眠定时到期会暂停并停止服务。安装与同步操作会写入模拟器，不能当作只读验证。

## 共同工作区

[MediaSuite](../../README.md) · [共同开发规范](../../AGENTS.md) · [品牌资源](../assets/branding/README.md)
