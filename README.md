# LA Agent

> 把大模型接进你的 Android 手机：对话、工具调用，以及通过 ROOT / Shizuku / 无障碍服务真正地操作设备。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B%20(API%2031)-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF.svg)](https://kotlinlang.org)
[![CI](https://github.com/Celraw/latools_agent/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/Celraw/latools_agent/actions/workflows/android.yml)

<p align="center">
  <img src="docs/icon/la-agent-icon-512.png" width="140" alt="LA Agent icon">
</p>

LA Agent 是一个运行在 Android 手机上的本地 Agent 客户端：你配置任意 **OpenAI 兼容** 的模型接口，
它就能在对话里调用几十个系统工具——查状态、设闹钟、定提醒、看位置、调音量/亮度、发短信、看日程，
以及在用户授权后通过无障碍服务截屏、点击、输入，代替你操作其他 App。

- 应用内不内置任何 API Key，所有配置和会话只保存在本机。
- 所有危险操作（shell 命令、拨号、发短信、安装应用等）都会经过确认或审批策略。
- 手机操控期间屏幕四周会出现霓虹边框，连按两次音量上键可随时中止。

## 功能特性

- **OpenAI 兼容接口**：自定义 Base URL / API Key / 模型，支持流式输出、思考链展示、400 自动降级重试。
- **60+ 内置工具**：时间提醒、位置导航、设备控制、状态查询、通讯日程、文件媒体、应用安装卸载，详见 [工具能力](#工具能力)。
- **三种特权通道**：ROOT / Shizuku / Dhizuku，按顺序自动选择可用通道；普通 API 就能完成的操作优先用系统 API。
- **AI 手机操控**：无障碍读取控件树、截屏、点击、滑动、输入文字；`ui_dump` 优先按节点操作，视觉模型可看截图。
- **审批模式**：需要批准 / 自动审查 / 完全权限三档，危险命令先解释风险再由用户决定。
- **会话管理**：流式气泡、Markdown（代码块/表格）、附件、历史搜索、消息分支、长按复制、上下文压缩。
- **权限可视化**：设置页里逐项授权，工具分组可整体开关；未授权的工具会明确提示而不是静默失败。
- **自适应应用图标**：深浅主题、圆形/方圆遮罩、Android 13 单色主题图标，见 [`docs/icon`](docs/icon)。

## 快速开始

### 环境要求

- Android 12 及以上（minSdk 31）
- JDK 21
- Android SDK Platform 37（compileSdk 37）
- 使用仓库自带的 Gradle Wrapper，无需额外安装 Gradle

### 构建

```bash
# Debug 包（可直接安装）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 包（需要签名，见下）
./gradlew :app:assembleRelease
```

### Release 签名（重要）

**签名文件永远不要提交到仓库。** 仓库里只提供模板：

```bash
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties，填入你自己的 jks 路径和密码
```

`.gitignore` 已排除 `keystore.properties`、`*.jks`、`*.keystore` 等所有签名与密钥文件。
没有 `keystore.properties` 时构建仍可进行，只是 release 包不会签名。

### 配置

1. 安装并打开 App，点右上角设置。
2. 填入 OpenAI 兼容的接口地址、API Key、模型名（例如 `https://api.example.com/v1`）。
3. 在「工具与权限」里按需授权（位置、通讯录、日历、短信、照片等）。
4. 如需系统级工具（改 WiFi/蓝牙/显示参数、安装应用等），在「特权通道」里启用 ROOT 或 Shizuku。

需要 Tavily Key 才会启用 `web_search`；关闭「模型支持识图」后，手机操控相关工具不会开放给模型。

## 工具能力

| 分组 | 工具 |
| --- | --- |
| 时间与提醒 | `get_time` `set_alarm` `set_timer` `query_alarm` `dismiss_alarm` `create_reminder` `list_reminders` `cancel_reminder` |
| 位置与导航 | `get_location` `navigate_to` |
| 设备控制 | `global_action` `flashlight` `set_volume` `media_control` `vibrate` `set_brightness` `set_display` `dnd` `system_toggle` `speak` `sensors` `open_uri` `open_settings` `app_details` `share_text` |
| 状态查询 | `get_status` `get_network` `list_notifications` `usage_stats` |
| 通讯与日程 | `search_contacts` `call` `send_sms` `calendar` |
| 文件与媒体 | `list_dir` `search_files` `recent_photos` `record_screen` |
| 高级与危险 | `install_apk` `uninstall_app` |
| 基础工具 | `run_shell` `get_device_info` `fetch_url` `http_request` `clipboard_get/set` `open_app` `screenshot` `read_file` `write_file` `list_packages` `app_control` `web_search`，以及手机操控系列（`phone_control_start/stop` `screen_snapshot` `tap` `swipe` `input_text` `ui_dump` 等） |

工具按分组开关，关掉的分组会直接从模型可见的工具列表里移除。

## 权限与隐私

### 权限说明

| 权限 | 用途 |
| --- | --- |
| 网络 / 网络状态 | 访问你配置的模型接口、Tavily 搜索、更新检查 |
| 通知 | 前台保活通知、到点提醒 |
| 位置（精确/粗略） | `get_location`，拒绝精确时退化为城市级 |
| 联系人 / 电话 / 短信 / 日历 | 查找联系人、拨号、发短信、查看与新建日程（逐项运行时授权） |
| 照片 / 文件 | 列出最近照片、读取应用目录与特权通道文件 |
| 无障碍服务 | 截屏、读取控件树、模拟点击与输入（仅在你授权后开启） |
| 修改系统设置 | 调节亮度等 |
| 通知读取 / 使用情况访问 / 勿扰 / 精确闹钟 | 对应的状态查询与提醒工具（需在系统设置里手动授予） |
| ROOT / Shizuku / Dhizuku | 执行特权命令与系统级开关（由你安装的授权工具提供） |

### 隐私

- API Key、模型配置、会话记录只保存在本机 SharedPreferences / 应用私有目录。
- 本项目**不包含遥测和统计**；仅有的外部请求是：你配置的模型接口、Tavily 搜索（可选）、
  更新检查（`LaCloudUpdate.kt` 中的 `latools.top`，只请求版本号，可在源码中替换或删除）。
- 工具返回的内容（位置、联系人、通知等）会随对话一起发送给你配置的模型接口，请自行评估隐私边界。

### 安全提示

- 本项目会请求敏感权限并提供 ROOT/Shizuku 命令执行能力，请只在自己的设备上使用，并审阅相关代码。
- 安装/卸载、发短信、拨出电话等操作会弹出确认；不要给不可信的第三方模型接口开放这些能力。
- 不要将 `keystore.properties`、`.jks`、API Key 或包含个人信息的日志提交到 Issue / PR。

## 项目结构

```text
app/src/main/java/com/latools/agent/
├── MainActivity.kt             # Compose 界面：对话、设置、历史
├── AgentEngine.kt              # OpenAI 兼容接口、流式解析、工具循环、通道执行器
├── AgentTools.kt               # 基础工具注册与执行
├── ExtTools.kt                 # 扩展工具注册表 / 分组 / 通用辅助
├── Ext*Tools.kt                # 时间、系统、状态、位置、通讯、文件、高级工具实现
├── ToolPermissions.kt          # 工具权限与特殊访问检查
├── AgentStore.kt               # 配置、会话、持久化、内置提示词
├── AgentAccessibilityService.kt# 无障碍：节点树、手势、截屏
├── PhoneControl.kt             # 手机操控生命周期、霓虹边框、点击光晕
├── LaCloudUpdate.kt            # 可选的应用内更新检查
└── AgentGuardService.kt        # 任务期间前台保活服务
```

## 开发

- 构建：`./gradlew :app:assembleDebug`
- 代码风格：Kotlin / Compose，注释使用中文；新增工具只需在对应 `Ext*Tools` 里加 schema + 执行分支。
- 提交前请确认没有提交签名文件、密钥或用户数据。
- 详见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [SECURITY.md](SECURITY.md)。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源，版权声明见 [NOTICE](NOTICE)。
你可以自由使用、修改、分发（包括商业用途），但需保留版权与许可声明。

## 免责声明

本项目按“现状”提供，不附带任何担保。使用 ROOT / Shizuku / 无障碍权限操作设备、安装应用、
修改系统设置等行为可能造成数据丢失或设备异常，请自行承担风险；因使用本项目产生的任何后果，
作者不承担责任。使用前请确认你拥有所操作设备的合法使用权，并遵守所在地区的法律法规。
