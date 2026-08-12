# PicoDockShortcut_chinese

Pico 4 系统 Dock(快捷栏)定制 LSPosed 模块。基于 [chaixshot/PicoDockShortcut](https://github.com/chaixshot/PicoDockShortcut) 的 fork 定制版,面向中文用户 + 修复了若干原版问题。

## 功能

- **自定义 Dock 应用列表**:拦截 `com.pvr.shortcut` 的 `dock_fix_apps.json`,用 GUI 配置你想固定的应用。
- **自定义图标**:拦截 `Image/custom_icon_<pkg>.png`,用应用真实图标替换。
- **运动中心(Fit Center)可控制**:运动中心是 Pico Dock 硬编码入口,原版 JSON 删不掉;本版把它变成一个可在 GUI 里开关的项。
- **自定义 Dock 背景图**:在 GUI 里选一张图片当 Dock 条背景,自带固定比例裁剪框,新手引导条同步同一张图。

## 相对原仓库改了什么(关键差异)

原版 `chaixshot/PicoDockShortcut` 存在几个问题,本 fork 做了针对性修复:

### 1. 运动中心(Fit Center)规则化 —— 全新功能
- **原版问题**:运动中心不是从 `dock_fix_apps.json` 读取的,而是由 `FixAppDataManager.addRemoveFitCenterApp(List,List)` 在 Dock 加载时**硬编码重新插入**的。所以原版 JSON 怎么改都删不掉它,也检测不到它。
- **本版**:hook 掉 `addRemoveFitCenterApp`,并按配置决定是否放行:
  - JSON 里存在 `{"packageName":"com.pvr.fitcenter","fitCenter":true}` → 放行(显示运动中心)
  - JSON 里没有该条目 → 拦截(隐藏运动中心)
- **GUI**:应用选择列表顶部新增"运动中心"可选项(合成条目),像普通应用一样能添加/删除/排序。
- 涉及文件:`HookInit.kt`、`AppModel.kt`、`MainActivity.kt`

### 2. 修复 "not hooked" 错误警告
- **原版问题**:用 `grep "com.hamer.dockshortcut" <target进程>/maps` 判断"是否已 hook"。但 **Vector/agent 注入模块时,模块 APK 路径并不出现在目标进程 maps 里**,导致永远误报"Target app not hooked",就算实际hook正常。
- **本版**:改为检查最新 LSPosed verbose 日志里是否有 `Hooking com.pvr.shortcut`(真正注入成功的证据),并检查目标进程是否存活。实测不再误报。
- 涉及文件:`MainActivity.kt`

### 3. 修复 Apply 后 "service timeout" 误报
- **原版问题**:`restartTargetApp` 用 `am startservice` 拉 `ShortcutService`,再用"服务是否在 dumpsys 里"判断是否成功。但 **ShortcutService 是绑定式服务**,只有被绑定时才出现在 dumpsys,所以总是超时报错。
- **本版**:不再依赖该检测,Apply 后直接 force-stop Dock 使其重载配置。

### 4. 修复 Apply 时 GUI 消失 / 抢焦点
- **原版问题**:Apply 用 `am start com.pvr.shortcut/.MainActivity` 拉起 Dock,但 Dock 是 `com.picovr.systemext` 控制的**系统 VR 浮动面板**,`am start` 会抢占窗口焦点,把管理器 GUI 顶掉。
- **本版**:Apply 只 `am force-stop` Dock(不主动 launch),让它下次被呼出时重载配置;**不抢焦点、GUI 保持可见**。并提示用户"按右手柄O呼出dock(约5秒后生效)"(Dock 冷启动需约 5 秒,之后秒出)。

### 5. 自定义 Dock 背景图 —— 全新功能

原版只能改 Dock 里的应用和图标,Dock 条本身是固定的纯色 `#FF1F1F1F`。本版让你换成任意图片。

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

## 应用列表默认配置

GUI 里编辑 `dock_fix_apps.json`(`/data/user/0/com.hamer.dockshortcut/dock_fix_apps.json`),示例:

```json
[
  {
    "packageName": "VirtualDesktop.Android",
    "className": "md59102214312e19799944a61bf7bc2f23e.VrActivity",
    "iconUrl": "Image/custom_icon_VirtualDesktop.Android.png"
  },
  {
    "packageName": "com.pvr.fitcenter",
    "className": "com.pvr.shortcut.utils.AppList$FitCenter",
    "fitCenter": true,
    "iconUrl": "Image/custom_icon_com.pvr.fitcenter.png"
  }
]
```

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
