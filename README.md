# PicoDockShortcut_chinese

Pico 4 系统 Dock(快捷栏)定制 LSPosed 模块。基于 [chaixshot/PicoDockShortcut](https://github.com/chaixshot/PicoDockShortcut),面向中文固件定制。

🌍 [简体中文](#picodockshortcut_chinese) · [English](#english) · [Русский](#русский)

## 功能

- **自定义 Dock 应用列表**:拦截 `com.pvr.shortcut` 的 `dock_fix_apps.json`,用 GUI 配置你想固定的应用。
- **拖拽排序**:长按图标拖拽调整 Dock 快捷栏顺序。
- **自定义图标**:从设备存储选择自己的图片,替换任意快捷方式的图标。
- **图标缓存**:优化图标缓存,显著加快 Dock 加载速度。
- **运动中心(Fit Center)可控制**:运动中心是 Pico Dock 硬编码入口,原版 JSON 删不掉;本版把它变成一个可在 GUI 里开关的项。
- **自定义 Dock 背景图**:在 GUI 里选一张图片当 Dock 条背景,自带固定比例裁剪框,新手引导条同步同一张图。
- **多语言支持**:内置 26+ 种语言,应用内语言选择器可覆盖系统默认语言。
- **自动重启**:应用更改后自动重启 Dock 服务,确保立即生效。
- **系统健康检查**:内置诊断检测 Root 访问和 LSPosed 状态,弹出明确提示。

## 前置需求

- **设备:** Pico 4 头显(支持 Phoenix/中国版固件)
- **权限:** 需要 **[Root 权限](https://pico4.wiki/guides/root/01-root/)** 修改系统文件
- **环境:** 需要安装 **[LSPosed 框架](https://github.com/JingMatrix/Vector/releases/tag/v2.0)**
- **作用域:** 在 LSPosed 模块作用域中勾选 `Dock` (`com.pvr.shortcut`)

## 如何使用

1. 在头显上安装 `PicoDockShortcut` APK
2. 在 LSPosed 管理器中启用模块
3. **选择作用域:** 确保勾选 `Dock` (`com.pvr.shortcut`)
4. **重启**设备或重启 `com.pvr.shortcut` 进程激活 hook
5. **打开 PicoDockShortcut:**
   - **添加应用:** 点击 `+` 槽位从已安装列表中选择应用
   - **换顺序:** 长按拖拽调整位置
   - **自定义图标:** 点击槽位左上角的图片图标,从存储中选图
   - **换应用:** 点击槽位主体替换为其他应用
   - **删除:** 点击右上角删除图标移除快捷方式
6. **应用更改:** 点击 **Apply** 按钮,应用会请求 Root 权限,保存配置并自动重启 Dock 服务。

## 更改不生效?

- 检查 LSPosed 模块是否激活
- 确保作用域选择了 `Dock` (`com.pvr.shortcut`)
- 确认已授予 PicoDockShortcut **Root 权限**
- 尝试完全重启设备

## 自定义图标如何工作?

应用将选择的图片保存到 `/data/user/0/com.hamer.dockshortcut/Image/Custom`。LSPosed hook 拦截 Dock 的图片请求,取而代之提供这些自定义文件。

## 自定义 Dock 背景图

Dock 条本身在系统里是固定的纯色 `#FF1F1F1F`,本模块让你换成任意图片。

![Dock 背景图演示](screenshots/dock_background_demo.jpeg)

**用法**:打开管理器 GUI → 底部"Dock 背景" → 选择背景图片 → 在裁剪框里拖动/缩放框选想要的区域 → 确认 → 呼出 Dock 生效。

**实现要点**:

- hook `com.pvr.shortcut.service.ShortcutViewContainer.inflateRootView(Context)`,拿到 Dock 根视图后替换背景。
- 目标视图定位:取 `dock_container`(`0x7f09009c`)下第一个 `id != 0x7f09005b` 的 `LinearLayout` 子节点 = 可见的 Dock 条;Guide(新手引导)= `0x7f09005b`,两者共用同一张 Bitmap、各自保留自己的圆角。
  - **不能直接改 `dock_container` 本身**——它是透明容器,给它上背景会把 Dock 条与 Guide 之间的透明空隙填满。
- 自定义 **RoundedBgDrawable : Drawable**,在 **onBoundsChange()** 里按真实 bounds 建 **BitmapShader**(Matrix center-crop,不变形)+ **Path.addRoundRect** 圆角。
  - inflate 阶段 View 还没测量(**width/height == 0**),所以**不能**在 hook 里直接预渲染 Bitmap,必须等 bounds 回调。
  - 圆角从原 **GradientDrawable** 提取(实测 38px),读不到时兜底 38f。
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

## 致谢

- [LSPosed Framework](https://github.com/LSPosed/LSPosed) - 强大的 hook 引擎
- [Jetpack Compose](https://developer.android.com/compose) - 现代声明式 UI 工具包
- [Material 3](https://m3.material.io/) - 优雅的设计组件

## 许可证

MIT(继承原仓库)。

---

# English

LSPosed module that customizes the Pico 4 system Dock (quick bar). Based on [chaixshot/PicoDockShortcut](https://github.com/chaixshot/PicoDockShortcut), customized for the Chinese firmware.

## Features

- **Custom Dock app list**: intercepts `com.pvr.shortcut`'s `dock_fix_apps.json` and lets you pin your own apps via the GUI.
- **Drag to Reorder**: easily organize your dock shortcuts with intuitive long-press and drag gestures.
- **Custom App Icons**: pick your own images from device storage to customize the look of any shortcut.
- **App Icon Cache**: optimized icon caching ensures significantly faster dock loading times.
- **Fit Center control**: Fit Center is a hard-coded Dock entry that the original JSON cannot remove; this version turns it into a toggle in the GUI.
- **Custom Dock background image**: pick an image in the GUI (with a fixed-ratio crop box); the onboarding guide bar uses the same image.
- **Language Support**: fully supports 26+ languages with an in-app selector to override system defaults.
- **Auto Restart**: automatically restarts the Dock service after applying changes to ensure they take effect immediately.
- **System Health Check**: built-in diagnostics detect Root access and LSPosed status, providing clear warning popups if requirements aren't met.

## Prerequisites

- **Device:** Pico 4 Headset (Phoenix/China firmware supported).
- **Permissions:** **[Root Access](https://pico4.wiki/guides/root/01-root/)** is required to apply changes to system files.
- **Environment:** **[LSPosed Framework](https://github.com/JingMatrix/Vector/releases/tag/v2.0)** must be installed and active.
- **Scope:** Ensure `Dock` (`com.pvr.shortcut`) is selected in the LSPosed module scope.

## How to use?

1. **Install** the `PicoDockShortcut` APK on your headset.
2. **Enable** the module in the LSPosed Manager.
3. **Select Scope:** Make sure `Dock` (`com.pvr.shortcut`) is checked in the module's scope settings.
4. **Reboot** your device or restart the `com.pvr.shortcut` process to activate the hooks.
5. **Open PicoDockShortcut:**
   - **Add App:** Tap the `+` slot to pick an app from the installed list.
   - **Reorder:** Long-press and drag any slot to change its position on the dock.
   - **Custom Icon:** Tap the image icon at the top-left of a slot to pick a custom image from storage.
   - **Change App:** Tap the app slot body to swap it with another app.
   - **Delete:** Tap the delete icon at the top-right to remove a shortcut.
6. **Apply:** Tap the **Apply** button. The app will request Root access, save the configuration, and restart the Dock service automatically.

## Why are my changes not appearing?

- Check if the LSPosed module is active.
- Ensure the `Dock` (`com.pvr.shortcut`) app is selected in the scope.
- Verify that you have granted **Root permissions** to PicoDockShortcut.
- Try a full device reboot if the service restart doesn't catch the changes.

## How do custom icons work?

The app saves your chosen images to `/data/user/0/com.hamer.dockshortcut/Image/Custom`. The LSPosed hook intercepts the Dock's request for assets and provides these custom files instead.

## Custom Dock background

The Dock bar is a fixed solid color (`#FF1F1F1F`) in the system. This module lets you replace it with any image.

![Dock background demo](screenshots/dock_background_demo.jpeg)

**Usage**: open the manager GUI → "Dock background" at the bottom → pick an image → drag/zoom to crop the area you want → confirm → summon the Dock to apply.

**Implementation notes**:

- Hooks `com.pvr.shortcut.service.ShortcutViewContainer.inflateRootView(Context)` and swaps the background once the root view is available.
- Target view: the first `LinearLayout` child of `dock_container` (`0x7f09009c`) whose `id != 0x7f09005b` = the visible Dock bar; the Guide (onboarding) = `0x7f09005b`. Both share the same Bitmap and keep their own corner radii.
  - **Never set the background on `dock_container` itself** — it is a transparent container; doing so fills the transparent gap between the Dock bar and the Guide.
- Custom **RoundedBgDrawable : Drawable** builds the **BitmapShader** (Matrix center-crop, no distortion) + **Path.addRoundRect** corners in **onBoundsChange()** using the real bounds.
  - At inflate time the view is not measured yet (**width/height == 0**), so you cannot pre-render the Bitmap in the hook; you must wait for the bounds callback.
  - Corner radius is read from the original **GradientDrawable** (measured 38px), falling back to 38f.
- The user image is stored at `/data/user/0/com.hamer.dockshortcut/dock_bg.png`; the directory must be 755 and the file 644, otherwise `com.pvr.shortcut` (running as system, uid 1000) cannot read it.

**About the crop ratio**: the Dock height is fixed (`main_view_height = 120dp`), the width is `wrap_content` and grows with the app count (left area ≈176dp + right area ≈138dp + 84dp per app), and it temporarily widens when "recent/running apps" appear. So the crop uses the system hard cap `dock_max_width 1800dp / 120dp = 15:1`, guaranteeing the right side never runs out of artwork when you add icons or open apps. The background is drawn **left-aligned**: the wider the Dock, the more of the image shows on the right.

## Build & deploy (short)

```bash
# Have the Android SDK locally with local.properties pointing at it, then:
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
# On device (Zygisk-Vector):
adb install -r app-debug.apk
adb shell su -c '/data/adb/lspd/cli modules enable com.hamer.dockshortcut'
adb shell su -c '/data/adb/lspd/cli scope add com.hamer.dockshortcut com.pvr.shortcut/0'
```

> Enabling the module/scope must be done via `vector-cli`, never by editing `modules_config.db` directly.

## Technical notes (reverse-engineering)

- **Fit Center entry**: `com.pvr.shortcut.dock.datamanager.FixAppDataManager.addRemoveFitCenterApp`, triggered when `DockUtils.isUserCenterNoFit()==true` (ToB or Chinese Phoenix firmware).
- **Actual apps**: `com.picovr.tobvrusercenter.MainActivity` / `com.picovr.vrusercenter.MainActivity`.
- **Dock panel**: `com.pvr.shortcut` is a VR floating panel (type 3002) controlled by `com.picovr.systemext`; it cannot be summoned with a plain `am start`.

## Special thanks to

- [LSPosed Framework](https://github.com/LSPosed/LSPosed) - For providing the powerful hooking engine.
- [Jetpack Compose](https://developer.android.com/compose) - For the modern declarative UI toolkit.
- [Material 3](https://m3.material.io/) - For the sleek design components.

## License

MIT (inherited from the upstream repo).

---

# Русский

LSPosed-модуль для настройки системного Dock (панели быстрого доступа) Pico 4. Основан на [chaixshot/PicoDockShortcut](https://github.com/chaixshot/PicoDockShortcut), адаптирован для китайской прошивки.

## Возможности

- **Свой список приложений в Dock**: перехватывает `dock_fix_apps.json` у `com.pvr.shortcut` и позволяет закрепить свои приложения через GUI.
- **Перетаскивание**: длительное нажатие и перетаскивание для изменения порядка.
- **Свои значки**: выбирайте свои изображения из хранилища устройства для настройки любого ярлыка.
- **Кэш значков**: оптимизированное кэширование значков для значительного ускорения загрузки Dock.
- **Управление Fit Center**: Fit Center — жёстко зашитый пункт Dock, который нельзя удалить через оригинальный JSON; эта версия превращает его в переключатель в GUI.
- **Свой фон Dock**: выберите изображение в GUI (с фиксированным соотношением сторон для кадрирования); панель подсказок (Guide) использует то же изображение.
- **Поддержка языков**: полная поддержка 26+ языков со встроенным переключателем языка.
- **Автоматический перезапуск**: автоматически перезапускает службу Dock после применения изменений.
- **Проверка работоспособности**: встроенная диагностика определяет Root-доступ и статус LSPosed.

## Как использовать

1. **Установите** APK `PicoDockShortcut` на гарнитуру.
2. **Включите** модуль в LSPosed Manager.
3. **Выберите область:** убедитесь, что `Dock` (`com.pvr.shortcut`) отмечен.
4. **Перезагрузите** устройство или перезапустите процесс `com.pvr.shortcut`.
5. **Откройте PicoDockShortcut:** добавляйте, перетаскивайте, меняйте значки.
6. **Примените:** нажмите **Apply** для сохранения и перезапуска Dock.

## Свой фон Dock

Полоса Dock в системе имеет фиксированный сплошной цвет (`#FF1F1F1F`). Этот модуль позволяет заменить его на любое изображение.

![Демонстрация фона Dock](screenshots/dock_background_demo.jpeg)

**Использование**: откройте GUI менеджера → «Dock background» внизу → выберите изображение →