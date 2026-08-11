# PicoDockShortcut_chinese

Pico 4 系统 Dock(快捷栏)定制 LSPosed 模块。基于 [chaixshot/PicoDockShortcut](https://github.com/chaixshot/PicoDockShortcut) 的 fork 定制版,面向中文用户 + 修复了若干原版问题。

## 功能

- **自定义 Dock 应用列表**:拦截 `com.pvr.shortcut` 的 `dock_fix_apps.json`,用 GUI 配置你想固定的应用。
- **自定义图标**:拦截 `Image/custom_icon_<pkg>.png`,用应用真实图标替换。
- **运动中心(Fit Center)可控制**:运动中心是 Pico Dock 硬编码入口,原版 JSON 删不掉;本版把它变成一个可在 GUI 里开关的项。

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
