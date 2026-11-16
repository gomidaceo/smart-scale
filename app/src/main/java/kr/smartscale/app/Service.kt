package kr.smartscale.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.Locale

/** 앱을 닫아도 체중계를 기다리고, 가족 기록을 받아 알림을 띄우는 백그라운드 서비스 */
class ScaleService : Service(), Hub.UiListener {

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        val n = Notifier.service(this, Hub.statusText)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(Notifier.ID_SERVICE, n)
        } catch (e: Exception) {
            Hub.addLog("백그라운드 시작 실패: ${e.message}")
            stopSelf()
            return
        }
        Hub.listeners.add(this)
        Hub.connect()
        Sync.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESYNC) Sync.start(this)
        if (intent?.action == ACTION_RECONNECT) Hub.connect()
        return START_STICKY
    }

    override fun onHubChanged(kind: Int) {
        if (kind == Hub.EV_STATE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Notifier.ID_SERVICE, Notifier.service(this, Hub.statusText))
        }
    }

    override fun onDestroy() {
        Hub.listeners.remove(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RESYNC = "kr.smartscale.app.RESYNC"
        const val ACTION_RECONNECT = "kr.smartscale.app.RECONNECT"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, ScaleService::class.java)
            if (action != null) i.action = action
            try { ctx.startForegroundService(i) } catch (e: Exception) {}
        }
    }
}

/** 폰을 켰을 때 서비스 다시 시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Hub.init(ctx)
        if (Hub.prefs.deviceAddr.isNotEmpty() || Hub.prefs.familyCode.isNotEmpty()) ScaleService.start(ctx)
    }
}

/** 「누구의 측정인가요?」 알림의 이름 버튼 */
class AssignReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Hub.init(ctx)
        val ts = intent.getLongExtra("ts", 0L)
        val uid = intent.getIntExtra("uid", -1)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(Notifier.ID_ASK)
        val m = Hub.lastMeasure
        if (m == null || m.ts != ts || uid < 0) return
        val wasVisible = Hub.activityVisible
        Hub.activityVisible = false
        Hub.assign(m, uid)
        Hub.activityVisible = wasVisible
    }
}

@Suppress("DEPRECATION")
object Notifier {
    const val CH_SERVICE = "service"
    const val CH_MEASURE = "measure"
    const val ID_SERVICE = 1
    const val ID_ASK = 3000

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val s = NotificationChannel(CH_SERVICE, "체중계 대기", NotificationManager.IMPORTANCE_MIN)
        s.description = "백그라운드에서 체중계를 기다리는 상태 표시"
        s.setShowBadge(false)
        nm.createNotificationChannel(s)
        val m = NotificationChannel(CH_MEASURE, "측정 알림", NotificationManager.IMPORTANCE_HIGH)
        m.description = "측정이 끝나면 알려줘요"
        nm.createNotificationChannel(m)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun service(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("내 체중계")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    fun measured(ctx: Context, r: Rec, p: Profile) {
        val b = BodyCalc.calc(r.weight, r.res, r.sfat, p)
        val title = "${p.display}님 측정 완료 · ${String.format(Locale.KOREA, "%.1f", r.weight)}kg"
        val parts = ArrayList<String>()
        if (b != null) {
            b.fatPct?.let { parts.add("체지방 ${String.format(Locale.KOREA, "%.1f", it)}%") }
            b.muscleKg?.let { parts.add("근육 ${String.format(Locale.KOREA, "%.1f", it)}kg") }
            parts.add("BMI ${String.format(Locale.KOREA, "%.1f", b.bmi)} (${BodyCalc.bmiLabel(b.bmi)})")
        }
        val prev = Hub.db.all(r.uid).firstOrNull { it.ts < r.ts - 1000 }
        if (prev != null) {
            val d = r.weight - prev.weight
            parts.add(0, "지난번보다 " + (if (d > 0.05) "▲" else if (d < -0.05) "▼" else "―") + String.format(Locale.KOREA, "%.1f", kotlin.math.abs(d)) + "kg")
        }
        val text = parts.joinToString(" · ")
        val n = Notification.Builder(ctx, CH_MEASURE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2000 + r.uid, n)
    }

    fun askWho(ctx: Context, m: Measure, people: List<Profile>) {
        val b = Notification.Builder(ctx, CH_MEASURE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("누구의 측정인가요? · ${String.format(Locale.KOREA, "%.1f", m.weight)}kg")
            .setContentText("이름을 누르면 그 사람 기록으로 저장돼요")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        people.take(3).forEach { p ->
            val i = Intent(ctx, AssignReceiver::class.java)
            i.putExtra("ts", m.ts)
            i.putExtra("uid", p.id)
            val pi = PendingIntent.getBroadcast(ctx, 100 + p.id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, p.display, pi).build())
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_ASK, b.build())
    }
}
