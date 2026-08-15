<div align="center">
<img src="Resource/icon.webp" width="128" height="128"/>

# PicoDockShortcut
### Manage your Pico 4 dock pinned shortcuts with ease.<br>Companion project for customizing your VR experience.
</div>

## 👓 Screenshot
<image src="Resource/NEW_Android_Pico2Dock.jpeg" width="800"/>
  
## 🌟 Key Features
*   **🤌 Drag to Reorder:** Easily organize your dock shortcuts with intuitive long-press and drag gestures.
*   **🖼�?Custom App Icons:** Pick your own images from device storage to customize the look of any shortcut.
*   **🚀 App Icon Cache:** Optimized icon caching ensures significantly faster dock loading times.
*   **🌐 Language Support:** Fully supports 26+ languages with an in-app selector to override system defaults.
*   **🔄 Auto Restart:** Automatically restarts the Dock service after applying changes to ensure they take effect immediately.
*   **🛡�?System Health Check:** Built-in diagnostics detect Root access and LSPosed status, providing clear warning popups if requirements aren't met.

## ⛏️ Prerequisites
*   **Device:** Pico 4 Headset (Phoenix/China firmware supported).
*   **Permissions:** **[Root Access](https://pico4.wiki/guides/root/01-root/)** is required to apply changes to system files.
*   **Environment:** **[LSPosed Framework](https://github.com/JingMatrix/Vector/releases/tag/v2.0)** must be installed and active.
*   **Scope:** Ensure `Dock` (`com.pvr.shortcut`) is selected in the LSPosed module scope.

## 📐 How to use?
1.  **Install** the `PicoDockShortcut` APK on your headset.
2.  **Enable** the module in the LSPosed Manager.
3.  **Select Scope:** Make sure `Dock` (`com.pvr.shortcut`) is checked in the module's scope settings.
4.  **Reboot** your device or restart the `com.pvr.shortcut` process to activate the hooks.
5.  **Open PicoDockShortcut:**
    *   **Add App:** Tap the `+` slot to pick an app from the installed list.
    *   **Reorder:** Long-press and drag any slot to change its position on the dock.
    *   **Custom Icon:** Tap the image icon at the top-left of a slot to pick a custom image from storage.
    *   **Change App:** Tap the app slot body to swap it with another app.
    *   **Delete:** Tap the delete icon at the top-right to remove a shortcut.
6.  **Apply:** Tap the **Apply** button. The app will request Root access, save the configuration, and restart the Dock service automatically.

## ⁉️ Why are my changes not appearing?
*   Check if the LSPosed module is active.
*   Ensure the `Dock` (`com.pvr.shortcut`) app is selected in the scope.
*   Verify that you have granted **Root permissions** to PicoDockShortcut.
*   Try a full device reboot if the service restart doesn't catch the changes.

## ⁉️ How do custom icons work?
The app saves your chosen images to `/data/user/0/com.hamer.dockshortcut/Image/Custom`. The LSPosed hook intercepts the Dock's request for assets and provides these custom files instead.

## 🔃 Language Support
The app supports multiple languages including English, Chinese (Simplified/Traditional), Thai, German, French, and more. You can override the system language using the **Language Selector** (globe icon) in the top-right corner.

## 🙏 Special thanks to:
*   [LSPosed Framework](https://github.com/LSPosed/LSPosed) - For providing the powerful hooking engine.
*   [Jetpack Compose](https://developer.android.com/compose) - For the modern declarative UI toolkit.
*   [Material 3](https://m3.material.io/) - For the sleek design components.
