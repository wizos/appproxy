package cn.ys1231.appproxy.mcpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.ys1231.appproxy.MainActivity
import cn.ys1231.appproxy.R

/**
 * MCP Server 前台服务
 *
 * 将 Netty HTTP Server 托管在前台服务中，确保 App 进入后台后
 * Android 系统不会挂起网络线程或终止进程，客户端始终可以连接。
 */
class MCPForegroundService : Service() {

    private val TAG = "iyue->${this.javaClass.simpleName}"
    // 与 IyueVPNService 的通知 ID(1) 区分开，避免覆盖
    private val NOTIFICATION_ID = 2
    private val CHANNEL_ID = "mcp_server_channel"

    /**
     * 向 MainActivity 暴露的 Binder，所有操作统一通过 Service 内部方法执行
     */
    inner class MCPServiceBinder : Binder() {
        fun startMcpServer() = this@MCPForegroundService.startMcpServer()
        fun stopMcpServer() = this@MCPForegroundService.stopMcpServer()
        fun updateMcpPort(port: Int?) = this@MCPForegroundService.updateMcpPort(port)
        fun updateMcpAuth(auth: String?) = this@MCPForegroundService.updateMcpAuth(auth)
    }

    private val binder = MCPServiceBinder()

    override fun onCreate() {
        super.onCreate()
        // 创建通知渠道（Android O 及以上版本需要）
        createNotificationChannel()
        Log.d(TAG, "onCreate: MCPForegroundService")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: MCPForegroundService")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: MCPForegroundService")
        // 立即提升为前台服务，防止系统在后台将进程降级
        // Android 14（API 34）起，Manifest 声明了 foregroundServiceType 后必须传入对应类型，否则抛 MissingForegroundServiceTypeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // 不需要系统在服务被杀后自动重启，App 重新打开时会由 Flutter 重新启动
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 App 时，主动停止服务，避免 Netty 服务器在后台残留
        Log.d(TAG, "onTaskRemoved: app exit, Stop MCPForegroundService")
        stopMcpServer()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: MCPForegroundService")
        // 服务被销毁时确保 Netty 服务器资源得到释放
        stopMcpServer()
    }

    // ---------- 私有服务方法，Binder 统一委托到这里 ----------

    private fun startMcpServer() {
        // stopMcpServer 会通过 stopForeground 移除通知，所以每次启动都需要重新调用 startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(true))
        }
        Log.d(TAG, "startMcpServer: Netty server started")
        MCPServer.getInstance(applicationContext).startMcpServer()
    }

    private fun stopMcpServer() {
        MCPServer.getInstance(applicationContext).stopMcpServer()
        // 移除前台通知，下次 startMcpServer 会重新调用 startForeground 补回来
        Log.d(TAG, "stopMcpServer: Netty server stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateMcpPort(port: Int?) {
        Log.d(TAG, "updateMcpPort: $port")
        MCPServer.getInstance(applicationContext).updateMcpPort(port)
    }

    private fun updateMcpAuth(auth: String?) {
        Log.d(TAG, "updateMcpAuth: $auth")
        MCPServer.getInstance(applicationContext).updateMcpAuth(auth)
    }

    // ---------- 通知相关 ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP Server",
            // 使用低优先级，避免在状态栏产生弹出提醒打扰用户
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AppProxy MCP Server"
            lightColor = Color.GREEN
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(running: Boolean = false): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = if (running) "MCP Server is running" else "MCP Server is not running"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${applicationInfo.loadLabel(packageManager)}-mcp")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.vpn_round)
            .setContentIntent(pendingIntent)
            // 设置为持续通知，不允许用户手动滑掉
            .setOngoing(true)
            .build()
    }
}
