// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 任务期间的前台保活服务。
 *
 * 为什么需要：Agent 的一次任务可能是「跑命令 → 截图 → 点按 → 再截图」这种长链路，
 * 中间只要息屏或切后台，系统就可能把进程回收，导致任务中断、甚至无障碍连接被断。
 * 前台服务把进程优先级抬到"用户可见"，配合通知让用户知道 AI 还在干活。
 *
 * 生命周期：
 * ```text
 * 会话开始（发送消息 / 手机操控开始）→ start()
 * 任务结束 / 用户停止 / 操控结束      → stop()
 * ```
 * 不做常驻：没有任务时持有前台服务属于白耗电，也会让用户觉得是流氓软件。
 */
class AgentGuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        return START_STICKY
    }

    private fun startForegroundSafe() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN).apply {
                    description = "AI 执行任务期间保持连接"
                    setShowBadge(false)
                },
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("LA Agent 正在执行任务")
            .setContentText("任务结束后会自动停止")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_ID = "agent_task"
        private const val NOTIFICATION_ID = 0x2A61

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AgentGuardService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentGuardService::class.java)) }
        }
    }
}
