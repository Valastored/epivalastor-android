package com.example.epivalastor

import java.text.Normalizer

// Mots qui signalent un examen dans le titre (ou dans une ligne « Type » de la description)
private val EXAM_REGEX = Regex(
    "(?<![\\p{L}\\d])(examens?|exams?|partiels?|qcm|contr[ôo]les?|ds|soutenances?|rattrapages?|midterms?|[ée]valuations?|interros?|interrogations?)(?![\\p{L}\\d])",
    RegexOption.IGNORE_CASE
)

// Jours fériés et vacances : pas de vrais cours
private val HOLIDAY_REGEX = Regex(
    "(?<![\\p{L}\\d])(f[ée]ri[ée]s?|vacances|cong[ée]s?|holidays?)(?![\\p{L}\\d])",
    RegexOption.IGNORE_CASE
)

/** true pour un jour férié ou une période de vacances (exclu des stats, notifs et widget). */
fun isHoliday(course: CourseEntity): Boolean = HOLIDAY_REGEX.containsMatchIn(course.title)

/** « 🌴 Vacances » ou « 🎉 Férié » */
fun holidayLabel(course: CourseEntity): String =
    if (normalizeForSearch(course.title).contains("vacances") || normalizeForSearch(course.title).contains("conge")) "🌴 Vacances"
    else "🎉 Férié"

/** true si le cours ressemble à un examen (partiel, QCM, contrôle, soutenance...). */
fun isExam(course: CourseEntity): Boolean {
    if (isHoliday(course)) return false
    if (EXAM_REGEX.containsMatchIn(course.title)) return true
    return course.description.replace("\\n", "\n").lines()
        .any { it.trim().startsWith("type", ignoreCase = true) && EXAM_REGEX.containsMatchIn(it) }
}

/** Nom du prof extrait de la description ICS (« Enseignant : ... »). */
fun courseTeacher(course: CourseEntity): String =
    course.description.replace("\\n", "\n").lines()
        .find { it.contains("Enseignant", true) || it.contains("Intervenant", true) }
        ?.substringAfter(":")?.trim() ?: ""

/** Minuscules sans accents, pour une recherche tolérante (« genie » trouve « Génie »). */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

/** 90 → « 1 h 30 », 45 → « 45 min » */
fun formatMinutes(totalMinutes: Long): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h ${"%02d".format(m)}"
    }
}
