# PicoDockShortcut_chinese

Pico 4 系统 Dock(快捷栏)定制 LSPosed 模块。基于 [chaixshot/PicoDockShortcut](https://github.com/chaixshot/PicoDockShortcut),面向中文固件定制。

## 功能

- **自定义 Dock 应用列表**:拦截 `com.pvr.shortcut` 的 `dock_fix_apps.json`,用 GUI 配置你想固定的应用。
- **自定义图标**:拦截 `Image/custom_icon_<pkg>.png`,用应用真实图标替换。
- **运动中心(Fit Center)可控制**:运动中心是 Pico Dock 硬编码入口,原版 JSON 删不掉;本版把它变成一个可在 GUI 里开关的项。
- **自定义 Dock 背景图**:在 GUI 里选一张图片当 Dock 条背景,自带固定比例裁剪框,新手引导条同步同一张图。

## 自定义 Dock 背景图

Dock 条本身在系统里是固定的纯色 `#FF1F1F1F`,本模块让你换成任意图片。

**用法**:打开管理器 GUI → 底部"Dock 背景" → 选择背景图片 → 在裁剪框里拖动/缩放框选想要的区域 → 确认 → 呼出 Dock 生效。

**实现要点**:

- hook `com.pvr.shortcut.service.ShortcutViewContainer.inflateRootView(Context)`,拿到 Dock 根视图后替换背景。
- 目标视图定位:取 `dock_container`(`0x7f09009c`)下第一个 `id != 0x7f09005b` 的 `LinearLayout` 子节点 = 可见的 Dock 条;Guide(新手引导)= `0x7f09005b`,两者共用同一张 Bitmap、各自保留自己的圆角。
  - **不能直接改 `dock_container` 本身**——它是透明容器,给它上背景会把 Dock 条与 Guide 之间的透明空隙填满。
- 自定义 `RoundedBgDrawable : Drawable`,在 `onBoundsChange()` 里按真实 bounds 建 `BitmapShader`(Matrix center-crop,不变形)+ `Path.addRoundRect` 圆角。
  - inflate 阶段 View 还没测量(`width/height == 0`),所以**不能**在 hook 里直接预渲染 Bitmap,必须等 bounds 回调。
  - 圆角从原 `GradientDrawable` 提取(实测 38px),读不到时兜底 38f。
- 用户图片存 `/data/user/0/com.hamer.dockshortcut/dock_bg.png`,目录 755 + 文件 644,否则以 system(uid 1000)运行的 `com.pvr.shortcut` 读不到。

**关于裁剪比例**:Dock 高度固定 `main_view_height = 120dp`,宽度是 `wrap_content` 随应用数量变化(左区约 176dp + 右区约 138dp + 每个应用 84dp),还会因"最近/运行中应用"临时变宽。所以裁剪按系统硬上限 `dock_max_width 1800dp / 120dp = 15:1` 出图,保证之后加图标或打开应用时右侧也不会缺画面。背景**左对齐**绘制,Dock 越宽右侧露出的画面越多。

## 构建与部署(简版)

```bash
# 本地已有 Android SDK 且 local.properties 指向它,然后:
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
# 设备(Zygisk-Vector):
adb install -r app-debug.apk
adb shell su -c '/data/adb/lspd/cli modules enable com.hamer.dockshortcut'
adb shell su -c '/data/adb/lspd/cli scope add com.hamer.dockshortcut com.pvr.shortcut/0'
```

> Vector 启用模块/scope 必须用 `vector-cli`,不能直接改 `modules_config.db`。

## 技术细节(逆向要点)

- **运动中心入口**:`com.pvr.shortcut.dock.datamanager.FixAppDataManager.addRemoveFitCenterApp`,触发条件是 `DockUtils.isUserCenterNoFit()==true`(ToB 或中国版 Phoenix)。
- **实际 app**:`com.picovr.tobvrusercenter.MainActivity` / `com.picovr.vrusercenter.MainActivity`。
- **Dock 面板**:`com.pvr.shortcut` 是 `com.picovr.systemext` 控制的 VR 浮动面板(type 3002),无法用普通 am start 强制呼出。

## 许可证

MIT(继承原仓库)。
