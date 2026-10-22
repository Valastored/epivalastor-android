package com.example.epivalastor

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Widget « Prochain cours » redimensionnable (de 1x1 à plein écran).
 * Trois mises en page selon la taille : petit (1x1, 1x2), bandeau (2x1, 3x1...), complet (≥ 3x2).
 * Redessiné par CourseNotifier à chaque échéance ; le compte à rebours tourne tout seul.
 */
class NextCourseWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAsync(context)
    }

    override fun onEnabled(context: Context) {
        refreshAsync(context)
    }

    // Avant Android 12 : on choisit nous-mêmes la mise en page quand la taille change
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                CourseNotifier.evaluateAndSchedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private enum class Size(val layout: Int) {
            TINY(R.layout.widget_next_course_small),   // 1x1 : titre + compte à rebours
            SMALL(R.layout.widget_next_course_small),  // 1x2 vertical : + heure / salle
            WIDE(R.layout.widget_next_course_wide),
            FULL(R.layout.widget_next_course)
        }

        /** Contenu calculé une fois, appliqué à chaque mise en page. */
        private class Content(
            val color: Int,
            val label: String,
            val title: String,
            val info: String,           // version complète (grand widget)
            val location: String,       // « 📍 salle » ou ""
            val ongoing: Boolean,
            val targetEpoch: Long?,     // fin du cours en cours, ou début du prochain
            val secondsLeft: Long?,     // secondes jusqu'à targetEpoch
            val dayWord: String,        // "" (aujourd'hui), "demain", "lun 27/10"
            val next: String?
        )

        private val HOUR_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY_FMT = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRANCE)

        // Dans les petits formats, le compte à rebours (avec secondes) n'est affiché que
        // pendant la dernière heure ; avant, on affiche simplement l'heure (plus court, plus lisible).
        private const val COMPACT_COUNTDOWN_MAX = 3600L

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, NextCourseWidget::class.java))

        fun hasWidgets(context: Context): Boolean = ids(context).isNotEmpty()

        private fun hhmm(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(PARIS_ZONE).format(HOUR_FMT)

        private fun buildContent(courses: List<CourseEntity>, now: Long): Content {
            val current = courses.firstOrNull { now >= it.startTimeEpoch && now < it.endTimeEpoch }
            val shown = current ?: courses.firstOrNull { it.startTimeEpoch > now }
                ?: return Content(0xFF9C27B0.toInt(), "EPIVALASTOR", "Aucun cours à venir", "", "",
                    false, null, null, "", null)

            val exam = isExam(shown)
            val color = if (exam) 0xFFD32F2F.toInt() else shown.colorArgb
            val location = if (shown.location.isBlank() || shown.location == "Non spécifié") "" else "📍 ${shown.location}"
            val today = LocalDate.now(PARIS_ZONE)
            val shownDate = courseDate(shown)
            val after = courses.firstOrNull { it.startTimeEpoch > shown.startTimeEpoch && courseDate(it) == shownDate }
            val next = after?.let { "Puis : ${it.title} à ${hhmm(it.startTimeEpoch)}" }
            val title = (if (exam) "📝 " else "") + shown.title

            return if (current != null) {
                Content(
                    color = color,
                    label = if (exam) "🔴 EXAMEN EN COURS" else "🔴 EN COURS",
                    title = title,
                    info = listOf(location, "fin à ${hhmm(shown.endTimeEpoch)}").filter { it.isNotEmpty() }.joinToString(" · "),
                    location = location,
                    ongoing = true,
                    targetEpoch = shown.endTimeEpoch,
                    secondsLeft = shown.endTimeEpoch - now,
                    dayWord = "",
                    next = next
                )
            } else {
                val dayWord = when (shownDate) {
                    today -> ""
                    today.plusDays(1) -> "demain"
                    else -> shownDate.format(DAY_FMT)
                }
                Content(
                    color = color,
                    label = if (exam) "📝 EXAMEN" else "PROCHAIN COURS",
                    title = title,
                    info = listOf(dayWord.replaceFirstChar { it.uppercase() }, "${hhmm(shown.startTimeEpoch)} – ${hhmm(shown.endTimeEpoch)}", location)
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    location = location,
                    ongoing = false,
                    targetEpoch = shown.startTimeEpoch,
                    secondsLeft = shown.startTimeEpoch - now,
                    dayWord = dayWord,
                    next = next
                )
            }
        }

        private fun showChrono(views: RemoteViews, secondsLeft: Long) {
            views.setViewVisibility(R.id.widget_chrono, View.VISIBLE)
            val base = SystemClock.elapsedRealtime() + secondsLeft.coerceAtLeast(0) * 1000
            views.setChronometer(R.id.widget_chrono, base, null, true)
            views.setChronometerCountDown(R.id.widget_chrono, true)
        }

        private fun buildViews(context: Context, size: Size, c: Content): RemoteViews {
            val views = RemoteViews(context.packageName, size.layout)
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, 3))
            views.setInt(R.id.widget_color_bar, "setBackgroundColor", c.color)
            views.setTextViewText(R.id.widget_title, c.title)

            // ---- Grand widget : tout le détail, compte à rebours jusqu'à 12 h avant ----
            if (size == Size.FULL) {
                views.setTextViewText(R.id.widget_label, c.label)
                views.setTextViewText(R.id.widget_info, c.info)
                views.setViewVisibility(R.id.widget_info, if (c.info.isEmpty()) View.GONE else View.VISIBLE)
                if (c.next != null) {
                    views.setViewVisibility(R.id.widget_next, View.VISIBLE)
                    views.setTextViewText(R.id.widget_next, c.next)
                } else {
                    views.setViewVisibility(R.id.widget_next, View.GONE)
                }
                val secs = c.secondsLeft
                if (secs != null && (c.ongoing || secs <= 12 * 3600)) {
                    views.setViewVisibility(R.id.widget_countdown_row, View.VISIBLE)
                    views.setTextViewText(R.id.widget_countdown_prefix, if (c.ongoing) "Reste " else "Dans ")
                    showChrono(views, secs)
                } else {
                    views.setViewVisibility(R.id.widget_countdown_row, View.GONE)
                }
                return views
            }

            // ---- Formats compacts (1x1, 1x2, 2x1, 3x1...) ----
            val secs = c.secondsLeft
            val target = c.targetEpoch
            if (secs == null || target == null) {
                // Aucun cours à venir
                views.setViewVisibility(R.id.widget_info, View.GONE)
                views.setViewVisibility(R.id.widget_countdown_row, View.GONE)
                return views
            }

            views.setViewVisibility(R.id.widget_countdown_row, View.VISIBLE)
            val useChrono = secs <= COMPACT_COUNTDOWN_MAX
            val prefix = when {
                c.ongoing && useChrono -> "reste"
                c.ongoing -> "fin"
                useChrono -> "dans"
                c.dayWord.isNotEmpty() -> c.dayWord
                else -> "à"
            }
            if (useChrono) {
                views.setViewVisibility(R.id.widget_static_value, View.GONE)
                showChrono(views, secs)
            } else {
                views.setViewVisibility(R.id.widget_chrono, View.GONE)
                views.setViewVisibility(R.id.widget_static_value, View.VISIBLE)
                views.setTextViewText(R.id.widget_static_value, hhmm(target))
            }

            when (size) {
                Size.WIDE -> {
                    // Gauche : titre + salle ; droite : « demain / 11:00 » ou « dans / 41:02 »
                    views.setTextViewText(R.id.widget_countdown_prefix, prefix)
                    views.setViewVisibility(R.id.widget_countdown_prefix, View.VISIBLE)
                    val info = c.location.ifEmpty { if (c.ongoing) "En cours" else "Prochain cours" }
                    views.setTextViewText(R.id.widget_info, info)
                    views.setViewVisibility(R.id.widget_info, View.VISIBLE)
                }
                Size.SMALL -> {
                    views.setTextViewText(R.id.widget_countdown_prefix, "$prefix ")
                    views.setViewVisibility(R.id.widget_countdown_prefix, View.VISIBLE)
                    views.setTextViewText(R.id.widget_info, c.location)
                    views.setInt(R.id.widget_info, "setMaxLines", 2)
                    views.setViewVisibility(R.id.widget_info, if (c.location.isEmpty()) View.GONE else View.VISIBLE)
                }
                else -> { // TINY : titre + heure ou compte à rebours, rien d'autre
                    views.setInt(R.id.widget_title, "setMaxLines", 1)
                    views.setViewVisibility(R.id.widget_countdown_prefix, View.GONE)
                    views.setViewVisibility(R.id.widget_info, View.GONE)
                }
            }
            return views
        }

        /** Choix de la mise en page d'après la taille (en dp) — utilisé avant Android 12. */
        private fun sizeFor(widthDp: Int, heightDp: Int): Size = when {
            widthDp >= 180 && heightDp >= 100 -> Size.FULL
            widthDp >= 100 -> Size.WIDE
            heightDp >= 80 -> Size.SMALL
            else -> Size.TINY
        }

        /** Dessine tous les widgets. `courses` = cours non terminés, triés, déjà filtrés. */
        fun render(context: Context, courses: List<CourseEntity>, now: Long) {
            val widgetIds = ids(context)
            if (widgetIds.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val content = buildContent(courses, now)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ : le lanceur choisit lui-même la bonne mise en page selon la taille
                val views = RemoteViews(
                    mapOf(
                        SizeF(40f, 40f) to buildViews(context, Size.TINY, content),
                        SizeF(40f, 80f) to buildViews(context, Size.SMALL, content),
                        SizeF(100f, 40f) to buildViews(context, Size.WIDE, content),
                        SizeF(180f, 100f) to buildViews(context, Size.FULL, content)
                    )
                )
                manager.updateAppWidget(widgetIds, views)
            } else {
                for (id in widgetIds) {
                    val options = manager.getAppWidgetOptions(id)
                    val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                    val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 40)
                    manager.updateAppWidget(id, buildViews(context, sizeFor(w, h), content))
                }
            }
        }
    }
}
