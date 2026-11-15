# 贡献指南

感谢你愿意为 LA Agent 提交代码、报告问题或改进文档。

## 提交 Issue

- 请说明设备型号、Android 版本、是否使用 ROOT / Shizuku，以及复现步骤。
- 涉及接口报错时，请贴出 HTTP 状态码与错误信息，**不要贴 API Key、签名文件或完整的隐私数据**。
- 功能建议请说明使用场景，而不是只写"加个功能"。

## 提交 Pull Request

1. Fork 本仓库并从 `main` 拉出分支，例如 `feat/my-tool`、`fix/clipboard-crash`。
2. 保持改动聚焦：一个 PR 只做一件事，避免混入格式化整仓的改动。
3. 提交前确保能构建通过：

   ```bash
   ./gradlew :app:assembleDebug
   ```

4. 新增工具时的约定：
   - 在 `Ext*Tools.kt` 对应分组里注册：`names` 集合 + `specs()` 里的 schema + `execute()` 分支；
   - 需要权限的工具在 `ToolPermissions.kt` 里补充检查，并返回明确的"去授权"提示，不要用 shell 绕过权限；
   - 会产生外部影响的操作（拨号、短信、安装、修改系统设置等）要经过 `confirm`；
   - 优先使用系统 API，只有普通应用无权完成的系统开关才走特权通道。
5. 新增文件请在文件头保留 SPDX 标识：

   ```kotlin
   // SPDX-License-Identifier: Apache-2.0
   // Copyright 2026 Celraw
   ```

## 不要提交的内容

- `keystore.properties`、`*.jks`、`*.keystore`、`*.p12`、`*.pem` 等签名与密钥文件；
- 真实的 API Key / Token / 账号密码；
- 包含个人位置、通讯录、短信、通知内容的日志或截图；
- `local.properties`、`.idea/`、`build/` 等本机或构建产物（已在 `.gitignore` 中排除）。

## 许可证

提交的代码默认按 [Apache License 2.0](LICENSE) 授权。
