// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 高级 / 危险操作：安装、卸载应用。
 *
 * 这两个动作都会改变系统的应用状态，且误操作代价高：
 *  · 一律要求用户确认（不受 AUTO 模式自动放行的豁免）；
 *  · 必须走 ROOT / Shizuku 通道（普通应用没有 INSTALL_PACKAGES 权限）。
 */
object AdvancedTools : ExtToolGroup {

    override val group = ToolGroup.ADVANCED

    private val names = setOf("install_apk", "uninstall_app")

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "install_apk",
            "安装一个本地 APK（覆盖安装请保持签名一致）。需要 ROOT/Shizuku 通道，且每次都会让用户确认。",
            toolProps("path" to toolProp("string", "APK 文件的绝对路径")),
            listOf("path"),
        )
        appendTool(
            out,
            "uninstall_app",
            "卸载一个应用（用户数据会一起删除）。需要 ROOT/Shizuku 通道，且每次都会让用户确认。",
            toolProps("package" to toolProp("string", "应用包名")),
            listOf("package"),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "install_apk" -> installApk(ctx, args)
        "uninstall_app" -> uninstallApp(ctx, args)
        else -> "未知工具：$name"
    }

    private suspend fun installApk(ctx: ToolCtx, args: JSONObject): String {
        val path = args.optString("path").trim()
        if (path.isBlank()) return "参数错误：缺少 path"
        if (!ctx.hasShell()) return "安装应用需要 ROOT 或 Shizuku 通道"
        if (!ctx.ask("安装 APK：$path\n确认安装？")) return "用户取消了安装"
        val r = ctx.shell("pm install -r ${shqExt(path)}", 90_000)
        return "安装结果：\n${r.output.take(1_500)}"
    }

    private suspend fun uninstallApp(ctx: ToolCtx, args: JSONObject): String {
        val pkg = args.optString("package").trim()
        if (pkg.isBlank()) return "参数错误：缺少 package"
        if (!ctx.hasShell()) return "卸载应用需要 ROOT 或 Shizuku 通道"
        if (!ctx.ask("卸载应用：$pkg（${ctx.appLabel(pkg)}）\n该应用的数据会一并删除，确认卸载？")) {
            return "用户取消了卸载"
        }
        val r = ctx.shell("pm uninstall --user 0 ${shqExt(pkg)}", 60_000)
        return "卸载结果：\n${r.output.take(1_500)}"
    }
}
