// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 位置与导航。
 *
 * 定位只用系统 LocationManager（不引入 Google Play Services，国内设备也能用）：
 *  · 先读 lastKnown 立刻返回，再尝试一次新鲜定位（默认 8 秒超时），避免工具卡住；
 *  · 用户只给了粗略定位时照样能用，返回精度让模型自己判断能不能用来导航；
 *  · 顺便做逆地理编码，模型拿到的是"杭州市西湖区…"这种人话，而不是裸坐标。
 */
object LocationTools : ExtToolGroup {

    override val group = ToolGroup.LOCATION

    private val names = setOf("get_location", "navigate_to")

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "get_location",
            "获取当前所在位置（经纬度、精度、地址、定位时间）。用户问“我在哪/附近有什么/导航”时先调用。需要「位置」权限。",
            toolProps(
                "fresh" to toolProp("boolean", "可选，是否强制拿一次新定位，默认 true"),
                "timeout_ms" to toolProp("integer", "可选，等待新定位的毫秒数，默认 8000"),
                "address" to toolProp("boolean", "可选，是否逆地理编码成地址，默认 true"),
            ),
        )
        appendTool(
            out,
            "navigate_to",
            "拉起地图导航到目的地。destination 可以是地名/地址，也可以直接给经纬度。",
            toolProps(
                "destination" to toolProp("string", "目的地名称或地址"),
                "latitude" to toolProp("number", "可选，目的地纬度"),
                "longitude" to toolProp("number", "可选，目的地经度"),
                "mode" to toolProp("string", "可选，driving / walking / bicycling / transit，默认 driving"),
            ),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "get_location" -> getLocation(ctx, args)
        "navigate_to" -> navigateTo(ctx, args)
        else -> "未知工具：$name"
    }

    private suspend fun getLocation(ctx: ToolCtx, args: JSONObject): String {
        ToolPermissions.check(ctx.context, ToolPerm.LOCATION)?.let { return it }
        val context = ctx.context
        val lm = context.getSystemService(LocationManager::class.java)
            ?: return "这台设备没有定位服务"
        if (!lm.isLocationEnabled) {
            return "系统定位总开关已关闭。请让用户打开定位（下拉快捷设置或 设置 → 位置信息）后重试。"
        }
        val fine = ToolPermissions.has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        // 只给了粗略定位时，优先网络定位；GPS 在只有 COARSE 权限时会直接抛 SecurityException
        val preferred = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        val providers = preferred.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            return "系统里没有可用的定位提供者（GPS/网络定位都被关闭）"
        }

        var best: Location? = providers.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        val wantFresh = args.optBoolean("fresh", true)
        if (wantFresh || best == null) {
            val timeoutMs = args.optLong("timeout_ms", 8_000L).coerceIn(1_000L, 20_000L)
            val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
                ?: LocationManager.PASSIVE_PROVIDER
            val fresh = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { runCatching { signal.cancel() } }
                    runCatching {
                        lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (fresh != null) best = fresh
        }

        val location = best ?: return "暂时拿不到定位（室内 GPS 可能较慢，可以再试一次；也可能是系统定位被关闭）\n" +
            shellLastLocation(ctx)

        val address = if (args.optBoolean("address", true)) reverseGeocode(context, location) else null
        val age = System.currentTimeMillis() - location.time
        return buildString {
            appendLine("位置：${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}")
            appendLine("精度：约 ±${location.accuracy.toInt()} 米（${location.provider ?: "未知来源"}）")
            appendLine("定位时间：${fmtTime(location.time)}" + if (age > 3_000) "（${fmtDuration(age)}前，可能不是最新）" else "（刚刚）")
            if (!address.isNullOrBlank()) appendLine("地址：$address")
            append("地图链接：https://uri.amap.com/marker?position=${location.longitude},${location.latitude}")
        }
    }

    private fun reverseGeocode(context: Context, location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.CHINA)
        val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        list?.firstOrNull()?.let { addr ->
            (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }
                .joinToString("").ifBlank { null }
        }
    }.getOrNull()

    /** 定位 API 失败时，用特权通道从 dumpsys 里捞最后一次已知位置（尽力而为）。 */
    private fun shellLastLocation(ctx: ToolCtx): String {
        if (!ctx.hasShell()) return ""
        val out = ctx.shell("dumpsys location | grep -i -m 3 -E 'last location|Last Known' ", 10_000)
        if (!out.ok || out.output.isBlank() || out.output.startsWith("（无输出）")) return ""
        return "系统缓存里的最后位置：\n" + out.output.take(600)
    }

    private fun navigateTo(ctx: ToolCtx, args: JSONObject): String {
        val destination = args.optString("destination").trim()
        val lat = if (args.has("latitude")) args.optDouble("latitude") else null
        val lng = if (args.has("longitude")) args.optDouble("longitude") else null
        if (destination.isBlank() && (lat == null || lng == null)) {
            return "参数错误：需要 destination，或同时给 latitude/longitude"
        }
        val mode = when (args.optString("mode").lowercase(Locale.ROOT)) {
            "walking", "walk", "w" -> "w"
            "bicycling", "bike", "b" -> "b"
            "transit", "bus", "r" -> "r"
            else -> "d"
        }
        val mapsIntent = if (lat != null && lng != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=$mode"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode"))
        }
        val result = launchIntent(ctx, mapsIntent, "打开导航")
        if (result.startsWith("已")) {
            return "$result${if (destination.isNotBlank()) "：$destination" else ""}（已交给地图应用，实际路线以地图显示为准）"
        }
        // Google 导航协议不一定有应用处理，退回通用 geo: 由用户选地图
        val geoIntent = if (lat != null && lng != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(destination.ifBlank { "目的地" })})"))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))
        }
        return launchIntent(ctx, geoIntent, "用地图打开目的地") +
            "（没有找到导航类应用，已改用通用地图协议）"
    }
}
