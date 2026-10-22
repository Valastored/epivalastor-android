package com.example.epivalastor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Period
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.util.concurrent.TimeUnit

class IcsManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Télécharge et lit un agenda ICS.
     * Retourne null en cas d'échec (réseau, erreur HTTP, fichier illisible) : l'appelant
     * doit alors garder les données existantes au lieu de tout effacer.
     */
    suspend fun fetchAndParseIcs(url: String): List<CourseEvent>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            // use {} ferme toujours la réponse (évite les fuites de connexions)
            val icsContent = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
            if (icsContent.isNullOrBlank()) return@withContext null

            val calendar = CalendarBuilder().build(StringReader(icsContent))
            val events = calendar.getComponents<VEvent>(Component.VEVENT)
            parseEvents(events)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseEvents(events: List<VEvent>): List<CourseEvent> {
        val result = mutableListOf<CourseEvent>()
        val usedKeys = mutableSetOf<String>()

        // Occurrences d'une série redéfinies individuellement (RECURRENCE-ID) :
        // on ne doit pas les générer une deuxième fois depuis la règle RRULE.
        val overrides = mutableMapOf<String, MutableSet<Long>>()
        for (event in events) {
            val uid = event.uid?.value ?: continue
            val recurrenceDate = event.recurrenceId?.date ?: continue
            overrides.getOrPut(uid) { mutableSetOf() }.add(recurrenceDate.time / 1000)
        }

        // Fenêtre dans laquelle on développe les événements récurrents
        val nowMs = System.currentTimeMillis()
        val window = Period(
            DateTime(nowMs - 365L * 24 * 3600 * 1000),
            DateTime(nowMs + 365L * 24 * 3600 * 1000)
        )

        for (event in events) {
            val start = event.startDate?.date ?: continue
            // .time donne directement l'instant absolu : aucune conversion de fuseau à faire
            val startSec = start.time / 1000
            val endSec = event.endDate?.date?.time?.div(1000) ?: (startSec + 3600)
            val durationSec = (endSec - startSec).coerceAtLeast(0)

            val uid = event.uid?.value
            val title = event.summary?.value ?: "Sans titre"
            val description = event.description?.value ?: ""
            val location = event.location?.value ?: "Non spécifié"

            fun add(baseKey: String, s: Long, e: Long) {
                var key = baseKey
                if (!usedKeys.add(key)) {
                    key = "$baseKey#$s"
                    usedKeys.add(key)
                }
                result.add(CourseEvent(key, title, description, location, s, e))
            }

            val recurrenceDate = event.recurrenceId?.date
            val rrule = event.getProperty<Property>(Property.RRULE)

            when {
                // Occurrence modifiée d'une série
                recurrenceDate != null -> add("${uid ?: title}#${recurrenceDate.time / 1000}", startSec, endSec)

                // Série récurrente : on génère chaque occurrence (EXDATE pris en compte par ical4j)
                rrule != null -> {
                    val periods = event.calculateRecurrenceSet(window)
                    for (period in periods) {
                        val occStart = period.start.time / 1000
                        if (uid != null && overrides[uid]?.contains(occStart) == true) continue
                        val occEnd = period.end?.time?.div(1000) ?: (occStart + durationSec)
                        add("${uid ?: title}#$occStart", occStart, occEnd)
                    }
                }

                // Événement simple
                else -> add(uid ?: "$title#$startSec", startSec, endSec)
            }
        }
        return result
    }
}
