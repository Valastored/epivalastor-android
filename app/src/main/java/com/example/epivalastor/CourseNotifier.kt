package com.example.epivalastor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")
private val HOUR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", Locale.FRANCE)

/** Intent qui ouvre l'app (optionnellement directement sur la liste des changements). */
internal fun openAppPendingIntent(context: Context, requestCode: Int, openChanges: Boolean = false): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (openChanges) putExtra(MainActivity.EXTRA_OPEN_CHANGES, true)
    }
    return PendingIntent.getActivity(
        context, requestCode, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/**
 * Gère les notifications de cours, même quand l'application est fermée.
 *
 * À chaque passage : on regarde l'état actuel (cours en cours, cours qui commence dans
 * moins de 15 min), on affiche/retire les notifications, puis on programme UNE alarme pour
 * la prochaine échéance. Pendant un cours, l'alarme revient chaque minute pour mettre à jour
 * la progression.
 */
object CourseNotifier {
    private const val PROGRESS_REFRESH = 60L       // Mise à jour de la notif en cours : chaque minute
    private const val ONGOING_NOTIF_ID = 1001
    // = Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16)
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val ALARM_REQUEST_CODE = 4242
    private const val ONGOING_CHANNEL = "ongoing_course_channel"
    private const val UPCOMING_CHANNEL = "upcoming_course_channel"
    private const val EXAM_CHANNEL = "exam_channel"
    private const val EXAM_EVE_HOUR = 19 // Rappel d'examen la veille à 19h

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** À appeler quand les cours ou les réglages changent (non bloquant). */
    fun update(context: Context) {
        val app = context.applicationContext
        scope.launch { evaluateAndSchedule(app) }
    }

    private fun upcomingId(course: CourseEntity) =
        ("${course.title}|${course.startTimeEpoch}".hashCode() and 0x7FFFFFFF) or 0x10000

    private fun examId(course: CourseEntity) =
        ("exam|${course.title}|${course.startTimeEpoch}".hashCode() and 0x7FFFFFFF) or 0x20000

    /** Veille de l'examen à 19h (heure de Paris). */
    private fun examEveEpoch(course: CourseEntity): Long =
        Instant.ofEpochSecond(course.startTimeEpoch).atZone(PARIS).toLocalDate()
            .minusDays(1).atTime(EXAM_EVE_HOUR, 0).atZone(PARIS).toEpochSecond()

    /** Calcul bloquant : à appeler sur un thread d'arrière-plan. */
    @Synchronized
    fun evaluateAndSchedule(context: Context) {
        try {
            val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            val hidden = prefs.getStringSet(AppSettings.KEY_HIDDEN, emptySet()) ?: emptySet()
            val ongoingOn = prefs.getBoolean(AppSettings.KEY_ONGOING, true)
            val liveUpdateOn = prefs.getBoolean(AppSettings.KEY_LIVE_UPDATE, true)
            val upcomingOn = prefs.getBoolean(AppSettings.KEY_UPCOMING, true)
            val examOn = prefs.getBoolean(AppSettings.KEY_EXAM, true)
            // Délai du rappel choisi dans les réglages (5, 10, 15, 30 ou 60 min)
            val UPCOMING_WINDOW = prefs.getInt(AppSettings.KEY_REMINDER_MIN, 15).coerceIn(1, 180) * 60L
            val hasWidgets = NextCourseWidget.hasWidgets(context)
            val now = System.currentTimeMillis() / 1000
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val courses = AppDatabase.getDatabase(context).courseDao()
                .getCoursesEndingAfterSync(now)
                .distinctBy { it.title to it.startTimeEpoch } // même cours présent dans 2 agendas
                .filter { it.title !in hidden }
                .filterNot { isHoliday(it) } // pas de rappel ni de widget pour un férié / des vacances

            // 1. Cours en cours
            val current = courses.firstOrNull { now >= it.startTimeEpoch && now < it.endTimeEpoch }
            if (ongoingOn && current != null) showOngoing(context, nm, current, now, liveUpdateOn)
            else nm.cancel(ONGOING_NOTIF_ID)

            // 2. Le cours a commencé : on retire son rappel « bientôt » s'il est encore affiché
            courses.filter { now >= it.startTimeEpoch }.forEach { nm.cancel(upcomingId(it)) }

            // 3. Cours qui commence bientôt (une seule alerte par cours)
            if (upcomingOn) {
                val notified = (prefs.getStringSet("notified_upcoming", emptySet()) ?: emptySet()).toMutableSet()
                courses.filter { (it.startTimeEpoch - now) in 1..UPCOMING_WINDOW }.forEach { c ->
                    val key = "${c.title}|${c.startTimeEpoch}"
                    if (key !in notified) {
                        showUpcoming(context, nm, c, now)
                        notified += key
                    }
                }
                notified.removeAll { key ->
                    val start = key.substringAfterLast('|').toLongOrNull()
                    start == null || start < now - 86_400
                }
                prefs.edit().putStringSet("notified_upcoming", notified).apply()
            }

            // 4. Examens : rappel la veille au soir (ou tout de suite si on a déjà passé 19h la veille)
            val exams = courses.filter { isExam(it) && it.startTimeEpoch > now }
            if (examOn) {
                val notifiedExams = (prefs.getStringSet("notified_exams", emptySet()) ?: emptySet()).toMutableSet()
                exams.forEach { c ->
                    val key = "${c.title}|${c.startTimeEpoch}"
                    if (now >= examEveEpoch(c) && key !in notifiedExams) {
                        showExamReminder(context, nm, c)
                        notifiedExams += key
                    }
                }
                notifiedExams.removeAll { key ->
                    val start = key.substringAfterLast('|').toLongOrNull()
                    start == null || start < now - 86_400
                }
                prefs.edit().putStringSet("notified_exams", notifiedExams).apply()
            }

            // 5. Widget écran d'accueil
            if (hasWidgets) {
                try { NextCourseWidget.render(context, courses, now) } catch (e: Exception) { e.printStackTrace() }
            }

            // 6. Prochaine échéance
            var next = Long.MAX_VALUE
            for (c in courses) {
                val candidates = buildList {
                    if (upcomingOn) { add(c.startTimeEpoch - UPCOMING_WINDOW); add(c.startTimeEpoch) }
                    if (ongoingOn || hasWidgets) { add(c.startTimeEpoch); add(c.endTimeEpoch) }
                    // Widget compact : passage de « 11:00 » au compte à rebours 1 h avant
                    if (hasWidgets) { add(c.startTimeEpoch - 3600); add(c.endTimeEpoch - 3600) }
                    if (examOn && isExam(c)) add(examEveEpoch(c))
                }
                for (t in candidates) if (t > now && t < next) next = t
            }
            if (ongoingOn && current != null) next = minOf(next, now + PROGRESS_REFRESH)

            if (next == Long.MAX_VALUE) cancelAlarm(context) else scheduleAlarm(context, next)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------- Alarmes ----------

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, CourseAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scheduleAlarm(context: Context, triggerEpoch: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerMs = triggerEpoch * 1000
        val pi = alarmIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmIntent(context))
    }

    // ---------- Notification « cours en cours » ----------

    private fun showOngoing(context: Context, nm: NotificationManager, course: CourseEntity, now: Long, liveUpdateOn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL, "Cours en direct", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        val totalMinutes = ((course.endTimeEpoch - course.startTimeEpoch) / 60).toInt().coerceAtLeast(1)
        val elapsedMinutes = ((now - course.startTimeEpoch) / 60).toInt().coerceIn(0, totalMinutes)
        val remainingMs = (course.endTimeEpoch - now) * 1000
        val endStr = Instant.ofEpochSecond(course.endTimeEpoch).atZone(PARIS).format(HOUR_FMT)
        val text = "📍 ${course.location} · fin à $endStr"

        val notification = if (liveUpdateOn && Build.VERSION.SDK_INT >= 36) {
            buildLiveUpdate(context, course, text, totalMinutes, elapsedMinutes, remainingMs)
        } else {
            NotificationCompat.Builder(context, ONGOING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle("🔴 En cours : ${course.title}")
                .setContentText(text)
                .setProgress(totalMinutes, elapsedMinutes, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openAppPendingIntent(context, 0))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(course.endTimeEpoch * 1000L)
                // Disparaît toute seule à la fin du cours, même si l'alarme arrive en retard
                .setTimeoutAfter(remainingMs)
                .build()
        }
        nm.notify(ONGOING_NOTIF_ID, notification)
    }

    /**
     * Android 16+ : « Live Update » (notification mise en avant, avec une pastille dans la
     * barre d'état qui affiche le temps restant en permanence).
     */
    @RequiresApi(36)
    private fun buildLiveUpdate(
        context: Context, course: CourseEntity, text: String,
        totalMinutes: Int, elapsedMinutes: Int, remainingMs: Long
    ): Notification {
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(
                listOf(Notification.ProgressStyle.Segment(totalMinutes).setColor(course.colorArgb))
            )
            .setProgress(elapsedMinutes)

        return Notification.Builder(context, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("En cours : ${course.title}")
            .setContentText(text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Demande à Android d'afficher la notif en « Live Update » (pastille dans la barre d'état)
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent(context, 0))
            // Le chronomètre décompte tout seul jusqu'à la fin du cours (affiché dans la pastille)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(course.endTimeEpoch * 1000L)
            .setTimeoutAfter(remainingMs)
            .build()
    }

    // ---------- Rappel d'examen ----------

    private fun showExamReminder(context: Context, nm: NotificationManager, course: CourseEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(EXAM_CHANNEL, "Examens (la veille)", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val start = Instant.ofEpochSecond(course.startTimeEpoch).atZone(PARIS)
        val today = java.time.LocalDate.now(PARIS)
        val whenStr = when (start.toLocalDate()) {
            today -> "aujourd'hui"
            today.plusDays(1) -> "demain"
            else -> start.format(DateTimeFormatter.ofPattern("EEEE dd/MM", Locale.FRANCE))
        }
        val notification = NotificationCompat.Builder(context, EXAM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("📝 Examen $whenStr : ${course.title}")
            .setContentText("à ${start.format(HOUR_FMT)} · 📍 ${course.location}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter((course.startTimeEpoch - System.currentTimeMillis() / 1000).coerceAtLeast(60) * 1000)
            .setContentIntent(openAppPendingIntent(context, 4))
            .build()
        nm.notify(examId(course), notification)
    }

    // ---------- Rappel « bientôt » ----------

    private fun showUpcoming(context: Context, nm: NotificationManager, course: CourseEntity, now: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(UPCOMING_CHANNEL, "Prochain cours (Alerte)", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val minutesLeft = ((course.startTimeEpoch - now + 59) / 60).coerceAtLeast(1)
        val startStr = Instant.ofEpochSecond(course.startTimeEpoch).atZone(PARIS).format(HOUR_FMT)

        val notification = NotificationCompat.Builder(context, UPCOMING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("⏳ Bientôt : ${course.title}")
            .setContentText("📍 ${course.location} · à $startStr (dans $minutesLeft min)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Retiré automatiquement quand le cours commence
            .setTimeoutAfter((course.startTimeEpoch - now) * 1000)
            .setContentIntent(openAppPendingIntent(context, 1))
            .build()
        nm.notify(upcomingId(course), notification)
    }
}

/** Notification envoyée quand une synchro détecte des changements dans l'emploi du temps. */
object ChangeNotifier {
    private const val CHANNEL = "schedule_changes_channel"
    private const val NOTIF_ID = 2001

    fun describe(change: ChangeEntity): String {
        fun d(e: Long?) = e?.let { Instant.ofEpochSecond(it).atZone(PARIS).format(DAY_FMT) } ?: "?"
        return when (change.changeType) {
            ChangeEntity.ADDED -> "🆕 ${change.title} · ${d(change.newStartEpoch)}"
            ChangeEntity.REMOVED -> "❌ ${change.title} annulé · ${d(change.oldStartEpoch)}"
            else -> buildString {
                append("✏️ ${change.title}")
                if (change.oldStartEpoch != change.newStartEpoch) append(" · ${d(change.oldStartEpoch)} → ${d(change.newStartEpoch)}")
                if (change.oldLocation != change.newLocation) append(" · salle ${change.newLocation}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyChanges(context: Context, changes: List<ChangeEntity>) {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppSettings.KEY_CHANGES, true)) return
        val hidden = prefs.getStringSet(AppSettings.KEY_HIDDEN, emptySet()) ?: emptySet()

        val relevant = changes
            .filter { it.title !in hidden }
            .distinctBy { listOf(it.changeType, it.title, it.oldStartEpoch, it.newStartEpoch) }
            .sortedBy { it.newStartEpoch ?: it.oldStartEpoch }
        if (relevant.isEmpty()) return

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Changements d'emploi du temps", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val title = if (relevant.size == 1) "📅 Changement dans ton emploi du temps"
                    else "📅 ${relevant.size} changements dans ton emploi du temps"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        relevant.take(6).forEach { style.addLine(describe(it)) }
        if (relevant.size > 6) style.setSummaryText("+ ${relevant.size - 6} autres")

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(describe(relevant.first()))
            .setStyle(style)
            .setNumber(relevant.size)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent(context, 2, openChanges = true))
            .build()
        nm.notify(NOTIF_ID, notification)
    }
}

/** Déclenché par l'alarme programmée : recalcule les notifications et programme la suivante. */
class CourseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                CourseNotifier.evaluateAndSchedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Les alarmes sont effacées au redémarrage ou à la mise à jour de l'app : on les reprogramme. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                Thread {
                    try {
                        CourseNotifier.evaluateAndSchedule(context.applicationContext)
                        DailySyncWorker.schedule(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
