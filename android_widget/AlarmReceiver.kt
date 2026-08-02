package com.example.cspi

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 재부팅 완료: 다음 알람 정확히 재등록 (삼성/HTC 등 OEM 변종 액션 포함)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                AlarmScheduler.rescheduleOnBoot(context)
            }

            // 자정 트리거: 내일 근무 알람 재등록
            "com.example.cspi.SET_DAILY_ALARM" -> {
                AlarmScheduler.scheduleNextDayAlarm(context)
                DailyAlarmManager.scheduleMidnightTrigger(context)
            }

            // 실제 기상 시각에 도달 → 풀스크린 알람 발생
            "com.example.cspi.WAKE_UP_ALARM" -> {
                val label = intent.getStringExtra("ALARM_LABEL") ?: "근무 기상 알람!"
                val shift = intent.getStringExtra("ALARM_SHIFT") ?: "D"
                fireAlarm(context, label, shift)
            }
        }
    }

    private fun fireAlarm(context: Context, label: String, shift: String) {
        // 채널 설정을 바꾸면 기존 채널은 갱신 안 되므로 버전 접미사로 새 채널 생성
        val channelId = "cspi_wake_alarm_v2"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 알림 채널 (소리 없음: 소리/진동은 AlarmActivity가 직접 제어, 채널은 화면 깨우기용)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(
                    channelId,
                    "근무 기상 알람",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "근무 시작 전 기상 알람"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setSound(null, null)  // 소리는 AlarmActivity가 재생 (중복 방지)
                }
                nm.createNotificationChannel(channel)
            }
        }

        // 알람 화면을 여는 인텐트
        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("ALARM_LABEL", label)
            putExtra("ALARM_SHIFT", shift)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val fullScreenPi = PendingIntent.getActivity(context, 7001, fullScreenIntent, piFlags)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("근무 기상 시간")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPi, true)  // 잠금화면 위로 화면 켜서 표시
            .setContentIntent(fullScreenPi)
            .build()

        nm.notify(7001, notification)

        // 폴백: 앱이 포그라운드일 때만 직접 실행 (백그라운드에선 fullScreenIntent가 처리)
        try {
            context.startActivity(fullScreenIntent)
        } catch (e: Exception) {
            // 백그라운드 startActivity 차단 시 무시 → fullScreenIntent가 화면을 켬
        }
    }
}

// 매일 자정 트리거 관리
object DailyAlarmManager {
    private const val REQUEST_CODE = 9999

    fun scheduleMidnightTrigger(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.cspi.SET_DAILY_ALARM"
        }
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 1)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    fun cancelMidnightTrigger(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.cspi.SET_DAILY_ALARM"
        }
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
    }
}
