package com.example.epivalastor

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Tous les réglages de l'app, sauvegardés automatiquement dans les SharedPreferences.
 * Les propriétés sont des états Compose : l'interface se met à jour dès qu'on les modifie.
 */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = mutableStateOf(prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM)
    var themeMode: String
        get() = _themeMode.value
        set(v) { _themeMode.value = v; prefs.edit().putString(KEY_THEME, v).apply() }

    private val _viewMode = mutableStateOf(prefs.getString(KEY_VIEW_MODE, "week") ?: "week")
    var viewMode: String
        get() = _viewMode.value
        set(v) { _viewMode.value = v; prefs.edit().putString(KEY_VIEW_MODE, v).apply() }

    private val _hiddenSubjects = mutableStateOf(prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet())
    var hiddenSubjects: Set<String>
        get() = _hiddenSubjects.value
        set(v) { _hiddenSubjects.value = v; prefs.edit().putStringSet(KEY_HIDDEN, v).apply(); notifChanged() }

    private val _ongoingNotif = mutableStateOf(prefs.getBoolean(KEY_ONGOING, true))
    var ongoingNotif: Boolean
        get() = _ongoingNotif.value
        set(v) { _ongoingNotif.value = v; prefs.edit().putBoolean(KEY_ONGOING, v).apply(); notifChanged() }

    private val _liveUpdate = mutableStateOf(prefs.getBoolean(KEY_LIVE_UPDATE, true))
    var liveUpdate: Boolean
        get() = _liveUpdate.value
        set(v) { _liveUpdate.value = v; prefs.edit().putBoolean(KEY_LIVE_UPDATE, v).apply(); notifChanged() }

    private val _upcomingNotif = mutableStateOf(prefs.getBoolean(KEY_UPCOMING, true))
    var upcomingNotif: Boolean
        get() = _upcomingNotif.value
        set(v) { _upcomingNotif.value = v; prefs.edit().putBoolean(KEY_UPCOMING, v).apply(); notifChanged() }

    private val _changeNotif = mutableStateOf(prefs.getBoolean(KEY_CHANGES, true))
    var changeNotif: Boolean
        get() = _changeNotif.value
        set(v) { _changeNotif.value = v; prefs.edit().putBoolean(KEY_CHANGES, v).apply() }

    private val _examNotif = mutableStateOf(prefs.getBoolean(KEY_EXAM, true))
    var examNotif: Boolean
        get() = _examNotif.value
        set(v) { _examNotif.value = v; prefs.edit().putBoolean(KEY_EXAM, v).apply(); notifChanged() }

    private val _reminderMinutes = mutableStateOf(prefs.getInt(KEY_REMINDER_MIN, 15))
    var reminderMinutes: Int
        get() = _reminderMinutes.value
        set(v) { _reminderMinutes.value = v; prefs.edit().putInt(KEY_REMINDER_MIN, v).apply(); notifChanged() }

    private val _nextBanner = mutableStateOf(prefs.getBoolean(KEY_NEXT_BANNER, true))
    var nextBanner: Boolean
        get() = _nextBanner.value
        set(v) { _nextBanner.value = v; prefs.edit().putBoolean(KEY_NEXT_BANNER, v).apply() }

    private fun notifChanged() = CourseNotifier.update(appContext)

    companion object {
        const val PREFS_NAME = "EpivalastorPrefs"
        const val KEY_THEME = "theme_mode"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_HIDDEN = "hidden_subjects"
        const val KEY_ONGOING = "enable_ongoing_notif"
        const val KEY_LIVE_UPDATE = "enable_live_update"
        const val KEY_UPCOMING = "enable_upcoming_notif"
        const val KEY_CHANGES = "enable_change_notif"
        const val KEY_EXAM = "enable_exam_notif"
        const val KEY_REMINDER_MIN = "reminder_minutes"
        const val KEY_NEXT_BANNER = "show_next_banner"
        val REMINDER_CHOICES = listOf(5, 10, 15, 30, 60)
        const val KEY_LAST_SYNC = "last_sync_epoch"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
