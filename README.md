# 小米智能家庭屏 Max 27：开发者选项入口助手

**Settings Entry Helper** 是一个小型 Android 工具，帮助小米智能家庭屏 Max 27（型号 **OH27**）打开被隐藏的开发者选项面板，再由用户手动开启开发者选项和 USB 调试。

**v1.4 已在一台 OH27 上实机验证成功：开发者面板打开后，手动启用 USB 调试，Mac 通过有线 ADB 获得了 `uid=2000(shell)` 会话。无需 root、刷机或解锁 bootloader。**

这不是一键 root、系统精简或性能优化工具，也不保证所有固件都适用。请仅在自己的设备或获授权的设备上使用。

## 下载

- [下载最新版 APK](https://github.com/marrymebaby/xiaomi-oh27-developer-options/releases/latest)
- [v1.4 发布页](https://github.com/marrymebaby/xiaomi-oh27-developer-options/releases/tag/v1.5)

下载附件 `SettingsEntryHelper-v1.4.apk`，不是 GitHub 自动生成的 Source code 压缩包。APK 使用开发调试证书签名，不是小米官方应用。

## 已验证环境

| 项目 | 实测环境 |
| --- | --- |
| 设备 | 小米智能家庭屏 Max 27 / OH27 |
| 系统 | Android 14 / API 34 |
| 固件界面显示 | OS 2.20122；工厂信息页显示 2.20.122 |
| Android 构建增量 | 177 |
| 助手版本 | 1.4 / versionCode 5 |
| 结果 | 打开开发者选项，手动启用 USB 调试，USB ADB shell 成功 |

验证日期：2026-10-30。当前仅有单台设备的验证记录，不代表全部 Max 27、其他小米屏幕或后续 OTA 都兼容。

## 使用方法

1. 将 APK 传到屏幕上，通过文件管理器安装；如系统要求，按提示允许该来源安装。首次使用不需要已有 ADB。
2. 打开 **Settings Entry Helper**，确认顶部显示 **1.4**。
3. 界面默认跟随系统语言，可用左下角「切换到中文 / Switch to English」实时切换；点击第一个按钮：**打开开发者面板（OH27 固件路由）**。
4. 在打开的系统开发者选项面板中，手动开启顶部总开关，并确认系统警告。
5. 如需连接电脑，再手动开启 **USB 调试**。
6. 使用支持数据传输的 USB 线连接电脑，只确认你信任的电脑的 RSA 授权提示。
7. 电脑安装 Android SDK Platform-Tools 后运行：

```sh
adb devices -l
adb shell id
```

设备状态应为 `device`，而不是 `unauthorized`；实测 `id` 返回 `uid=2000(shell)`。这不是 root。

**主流程不需要无障碍权限，不需要点击版本号，不需要进入工厂测试菜单，也不需要使用下方的写入或组件探测按钮。**

不要开启 OEM 解锁，不要随意调整其他开发者选项。使用完毕可关闭 USB 调试，或在系统设置中撤销电脑调试授权。

## 为什么普通入口没有反应？

在实测固件中，自定义“关于设备”页面没有提供可用的版本号连击入口。标准开发者设置 Intent 会落到一个提示“请先启用开发者选项”后退出的页面；正常的开发者面板 Activity 被禁用，但面板 Fragment 仍保留在系统设置的允许列表中。

v1.4 使用同一设置应用中导出的容器：

```text
com.android.settings/com.android.settings.Settings$WifiSettingsActivity
```

并传入系统已有的页面参数：

```text
:settings:show_fragment=com.android.settings.development.DevelopmentSettingsDashboardFragment
:settings:show_fragment_title=Developer options
```

该固件上的实际容器没有固定 Fragment 元数据，也没有覆盖继承的 Intent 路由行为。助手不设置 Intent action，也不添加 `NEW_TASK` 标志，避免走另一条双栏深链接路由；启动前检查容器的 exported/enabled 状态及固定页面元数据。

助手只请求打开系统页面，**由系统设置应用显示开关，由用户手动确认**；不自动写入调试开关。系统面板本身在开发者模式关闭时会初始化或重置部分开发者设置，因此“打开页面”并不等于完全没有系统侧副作用。

## 失败时怎么办？

- **仍提示未启用开发者选项**：确认点击的是第一个带 `OH27 firmware route` 的按钮，不是 `standard intent`。
- **打开 Wi-Fi、白屏、闪退或返回助手**：停止尝试；固件路由可能不同。记录型号、系统版本、页面截图和助手底部 `[FIRMWARE ROUTE]` 日志。
- **ADB 显示 unauthorized**：在设备屏幕上确认自己电脑的调试授权。
- **ADB 列表为空**：确认 USB 调试已打开、线材支持数据、连接端口正确。MTP 文件传输可用不代表 ADB 已启用。
- **出现 SecurityException**：该固件可能封闭了入口，或所点功能需要普通应用不具备的权限；不要把其他按钮当作强制提权工具。

反馈前请去除日志和截图中的序列号、IP、账户信息及其他个人数据。不要上传导出的厂商 APK 或完整设备诊断包。

## 其他诊断功能与隐私

为了保留排查过程，本版还包含以下功能，但它们不是 OH27 成功开启开发者模式的必要步骤：

- 显示开发者模式和 ADB 状态、系统信息，探测部分设置页面。
- 两个 `Try enabling ...` 按钮尝试写入 Android 全局设置；普通侧载应用通常会收到 `SecurityException`，主流程不要使用它们。
- 可选的版本号连击无障碍助手，需要用户手动启用并在应用中准备；事件范围限定为 `com.android.settings` 和 `com.miui.securitycenter`。使用后可停用服务，OH27 的成功路径不需要它。
- 用户主动选择导出时，可复制 Settings / FactoryMode 的 APK 代码、组件声明和构建信息到所选 ZIP，不复制应用私有数据；导出资料可能包含设备标识，应留在本地。
- 日志仅在应用内展示或保存；复制和分享需用户主动操作。应用未申请网络权限，没有联网遥测。

本仓库不包含小米/联发科系统 APK、设备导出 ZIP、用户数据、设备诊断日志或签名私钥。

## 从源码构建

依赖：JDK（需提供 `javac` 和 `keytool`）、Android SDK Platform 35、Build-Tools 35.0.0。无需 Gradle，构建脚本调用 `aapt2`、`javac`、`d8`、`zipalign` 和 `apksigner`。

```sh
git clone https://github.com/marrymebaby/xiaomi-oh27-developer-options.git
cd xiaomi-oh27-developer-options

# 如 SDK 不在默认位置，请修改为自己的路径。
export ANDROID_HOME="$HOME/Library/Android/sdk"

# 仅在本机没有调试签名文件时创建；不要覆盖已有签名文件。
if [ ! -f "$HOME/.android/debug.keystore" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$HOME/.android/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

sh build.sh
```

输出为 `SettingsEntryHelper.apk`。可用 `ANDROID_HOME`、`BUILD_TOOLS`、`PLATFORM`、`KEYSTORE` 环境变量覆盖构建路径；脚本的密钥别名和口令按 Android 默认调试证书约定设置。

发布 APK 的签名私钥不随仓库分发。自行构建时使用的是自己的证书，通常不能覆盖安装发布版本；如需卸载旧版再装，请注意旧版助手自身的数据会被删除。构建工具、签名证书及时间等也会使 APK 的哈希不同。

## 安全边界

- 不提供 root、bootloader 解锁、内核漏洞利用或自动精简脚本。
- 能开启 ADB 不代表可以无风险停用所有系统应用；请保留系统桌面、SystemUI、设置、网络、安装器和需要的智能家居功能。
- 与小米及其他厂商无隶属或背书关系。系统升级可能使此入口失效。
