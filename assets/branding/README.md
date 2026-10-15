# 夜莺播放器 · Nightingale

应用名称保持「夜莺播放器」，使用与 Windows 版相同的月牙、闭眼青鸟图标。母版由 `gpt-image-2` 于 2026-10-13 以 high 质量生成，提示词见 `nightingale.prompt.txt`。

母版保存在 `app/src/main/res/drawable-nodpi/nightingale_player_icon.png`；桌面入口引用带安全留白的 `nightingale_player_icon_adaptive.png`。两者均为 1024×1024 PNG，不预裁圆角。

Android 自适应图标使用 108dp 画布，静止遮罩约占 72dp，关键图形需放入中央 66dp 安全圆。当前将母版缩放至画布的 70%，以背景边缘色延展留白；不要直接用母版替换自适应位图。

本机重新适配命令（从项目目录运行）：

```powershell
python -B D:\tools\player-branding\build_launcher_icon.py --source app/src/main/res/drawable-nodpi/nightingale_player_icon.png --output app/src/main/res/drawable-nodpi/nightingale_player_icon_adaptive.png --preview-dir tmp/branding --name nightingale --scale 0.70
```

修改母版后检查圆形、圆角方形遮罩和安全区预览。Android 的 applicationId 和数据存储标识保持不变，更新图标不影响现有设置。
