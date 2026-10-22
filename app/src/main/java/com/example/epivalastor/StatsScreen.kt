package com.example.epivalastor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StatsPeriod(val label: String) {
    WEEK("Cette semaine"), NEXT_WEEK("Semaine pro."), MONTH("Ce mois"), ALL("Tout")
}

private data class Bar(val label: String, val minutes: Long, val color: Color)

/** Statistiques : heures de cours par matière, par agenda, avancement de la semaine... */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(courses: List<CourseEntity>, nowEpoch: Long, onClose: () -> Unit) {
    BackHandler { onClose() }
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    val today = LocalDate.now(PARIS_ZONE)

    val subset = remember(courses, period, today) {
        val monday = today.with(DayOfWeek.MONDAY)
        courses.filter { c ->
            // Jours fériés et vacances ne comptent pas comme des cours
            if (isHoliday(c)) return@filter false
            val d = courseDate(c)
            when (period) {
                StatsPeriod.WEEK -> !d.isBefore(monday) && !d.isAfter(monday.plusDays(6))
                StatsPeriod.NEXT_WEEK -> !d.isBefore(monday.plusDays(7)) && !d.isAfter(monday.plusDays(13))
                StatsPeriod.MONTH -> d.year == today.year && d.month == today.month
                StatsPeriod.ALL -> true
            }
        }
    }

    fun minutesOf(c: CourseEntity) = ((c.endTimeEpoch - c.startTimeEpoch) / 60).coerceAtLeast(0)

    val totalMinutes = subset.sumOf { minutesOf(it) }
    val doneMinutes = subset.sumOf { c ->
        when {
            nowEpoch >= c.endTimeEpoch -> minutesOf(c)
            nowEpoch > c.startTimeEpoch -> (nowEpoch - c.startTimeEpoch) / 60
            else -> 0L
        }
    }
    val days = subset.groupBy { courseDate(it) }
    val examCount = subset.count { isExam(it) }

    val bySubject = subset.groupBy { it.title }
        .map { (title, list) -> Bar(title, list.sumOf { minutesOf(it) }, Color(list.first().colorArgb)) }
        .sortedByDescending { it.minutes }
    val byAgenda = subset.groupBy { it.icsName }
        .map { (name, list) -> Bar(name, list.sumOf { minutesOf(it) }, Color(list.first().colorArgb)) }
        .sortedByDescending { it.minutes }

    val dayFmt = DateTimeFormatter.ofPattern("EEEE dd/MM", Locale.FRANCE)
    val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
    val longestDay = days.maxByOrNull { (_, list) -> list.sumOf { minutesOf(it) } }
    val earliest = subset.minByOrNull { java.time.Instant.ofEpochSecond(it.startTimeEpoch).atZone(PARIS_ZONE).toLocalTime() }
    val latest = subset.maxByOrNull { java.time.Instant.ofEpochSecond(it.endTimeEpoch).atZone(PARIS_ZONE).toLocalTime() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistiques", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) })
                    }
                }
            }

            if (subset.isEmpty()) {
                item {
                    Text(
                        "Aucun cours sur cette période.",
                        modifier = Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                return@LazyColumn
            }

            // Chiffres clés
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Heures", formatMinutes(totalMinutes), Modifier.weight(1f))
                    StatTile("Cours", subset.size.toString(), Modifier.weight(1f))
                    StatTile("Jours", days.size.toString(), Modifier.weight(1f))
                    StatTile("Examens", examCount.toString(), Modifier.weight(1f), highlight = examCount > 0)
                }
            }

            // Avancement
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Avancement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                val fraction = if (totalMinutes > 0) doneMinutes.toFloat() / totalMinutes else 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${formatMinutes(doneMinutes)} faites · ${formatMinutes((totalMinutes - doneMinutes).coerceAtLeast(0))} restantes (${(fraction * 100).toInt()} %)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text("Heures par matière", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
            }
            items(bySubject, key = { "s_" + it.label }) { bar -> BarRow(bar, bySubject.first().minutes) }

            if (byAgenda.size > 1) {
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("Heures par agenda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(byAgenda, key = { "a_" + it.label }) { bar -> BarRow(bar, byAgenda.first().minutes) }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                Text("Journées", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                longestDay?.let { (date, list) ->
                    ListItem(
                        headlineContent = { Text("Journée la plus chargée") },
                        supportingContent = { Text(date.format(dayFmt).replaceFirstChar { it.uppercase() }) },
                        trailingContent = { Text(formatMinutes(list.sumOf { minutesOf(it) }), fontWeight = FontWeight.Bold) }
                    )
                }
                earliest?.let { c ->
                    ListItem(
                        headlineContent = { Text("Début le plus tôt") },
                        supportingContent = { Text("${c.title} · ${courseDate(c).format(dayFmt)}") },
                        trailingContent = {
                            Text(java.time.Instant.ofEpochSecond(c.startTimeEpoch).atZone(PARIS_ZONE).format(hourFmt), fontWeight = FontWeight.Bold)
                        }
                    )
                }
                latest?.let { c ->
                    ListItem(
                        headlineContent = { Text("Fin la plus tardive") },
                        supportingContent = { Text("${c.title} · ${courseDate(c).format(dayFmt)}") },
                        trailingContent = {
                            Text(java.time.Instant.ofEpochSecond(c.endTimeEpoch).atZone(PARIS_ZONE).format(hourFmt), fontWeight = FontWeight.Bold)
                        }
                    )
                }
                if (days.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text("Moyenne par jour de cours") },
                        trailingContent = { Text(formatMinutes(totalMinutes / days.size), fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) EXAM_RED.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (highlight) EXAM_RED else MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun BarRow(bar: Bar, maxMinutes: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(bar.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatMinutes(bar.minutes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(3.dp))
        val fraction = if (maxMinutes > 0) bar.minutes.toFloat() / maxMinutes else 0f
        Box(
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp)).background(bar.color)
            )
        }
    }
}
