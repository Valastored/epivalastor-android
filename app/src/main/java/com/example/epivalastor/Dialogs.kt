package com.example.epivalastor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChangesDialog(
    changes: List<ChangeEntity>,
    onDismiss: () -> Unit,
    onMarkAllSeen: () -> Unit,
    onChangeClick: (ChangeEntity) -> Unit
) {
    val dateFormatter = DateTimeFormatter.ofPattern("EEE dd/MM à HH:mm", Locale.FRANCE)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    fun fmt(epoch: Long?) = epoch?.let { Instant.ofEpochSecond(it).atZone(PARIS_ZONE).format(dateFormatter) } ?: "?"
    fun fmtTime(epoch: Long?) = epoch?.let { Instant.ofEpochSecond(it).atZone(PARIS_ZONE).format(timeFormatter) } ?: "?"

    // On retient ce qui était « nouveau » à l'ouverture, puis on marque tout comme vu
    val unseenAtOpen = remember { changes.filter { !it.seen }.map { it.id }.toSet() }
    LaunchedEffect(Unit) { onMarkAllSeen() }

    // Un même cours présent dans deux agendas ne doit apparaître qu'une fois
    val displayed = remember(changes) {
        changes.distinctBy { listOf(it.changeType, it.title, it.oldStartEpoch, it.newStartEpoch) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔔 Changements récents", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            if (displayed.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun changement détecté sur les 14 prochains jours.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 450.dp).fillMaxWidth()) {
                    items(displayed) { change ->
                        val isNew = change.id in unseenAtOpen
                        val (label, labelColor) = when (change.changeType) {
                            ChangeEntity.ADDED -> "🆕 Ajouté" to Color(0xFF34A853)
                            ChangeEntity.REMOVED -> "❌ Annulé" to Color(0xFFEA4335)
                            else -> "✏️ Modifié" to Color(0xFFFF9800)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .then(
                                    if (isNew) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .clickable(enabled = change.changeType != ChangeEntity.REMOVED) { onChangeClick(change) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(change.colorArgb)))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(label, color = labelColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    if (isNew) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("• nouveau", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = change.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    textDecoration = if (change.changeType == ChangeEntity.REMOVED) TextDecoration.LineThrough else null
                                )
                                if (change.oldTitle != null) {
                                    Text("Ancien nom : ${change.oldTitle}", style = MaterialTheme.typography.bodySmall)
                                }

                                when (change.changeType) {
                                    ChangeEntity.ADDED -> Text(
                                        "📅 ${fmt(change.newStartEpoch)} – ${fmtTime(change.newEndEpoch)} | 📍 ${change.newLocation ?: ""}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    ChangeEntity.REMOVED -> Text(
                                        "📅 ${fmt(change.oldStartEpoch)} – ${fmtTime(change.oldEndEpoch)} | 📍 ${change.oldLocation ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                    else -> {
                                        val timeChanged = change.oldStartEpoch != change.newStartEpoch || change.oldEndEpoch != change.newEndEpoch
                                        if (timeChanged) {
                                            Text(
                                                "📅 ${fmt(change.oldStartEpoch)} – ${fmtTime(change.oldEndEpoch)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                textDecoration = TextDecoration.LineThrough
                                            )
                                            Text(
                                                "➡️ ${fmt(change.newStartEpoch)} – ${fmtTime(change.newEndEpoch)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else {
                                            Text("📅 ${fmt(change.newStartEpoch)} – ${fmtTime(change.newEndEpoch)}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (change.oldLocation != change.newLocation) {
                                            Text(
                                                "📍 ${change.oldLocation ?: "?"} ➡️ ${change.newLocation ?: "?"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

/** Choix après génération d'une image de planning : partager ou enregistrer dans la galerie. */
@Composable
fun PlanningImageDialog(image: PlanningImage, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val courseCount = image.days.sumOf { (_, list) -> list.count { !isHoliday(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Image du planning", fontWeight = FontWeight.Bold) },
        text = {
            Text("${image.title}\n${image.days.size} jour${if (image.days.size > 1) "s" else ""} · $courseCount cours")
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSave) { Text("Enregistrer") }
                TextButton(onClick = onShare) { Text("Partager") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun CourseDetailsDialog(course: CourseEntity, nowEpoch: Long, agendaColors: Map<String, Int>, onHideSubject: (String) -> Unit, onDismiss: () -> Unit) {
    val startInstant = Instant.ofEpochSecond(course.startTimeEpoch).atZone(PARIS_ZONE)
    val endInstant = Instant.ofEpochSecond(course.endTimeEpoch).atZone(PARIS_ZONE)
    val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
    val dayFmt = DateTimeFormatter.ofPattern("EEEE dd MMMM", Locale.FRANCE)
    val timeStr = "${startInstant.format(hourFmt)} - ${endInstant.format(hourFmt)}"
    val cleanDesc = course.description.replace("\\n", "\n").trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // Chaque agenda dans sa propre couleur (« EPITA & GDG »)
                val names = course.icsName.split(" & ")
                Text(
                    text = buildAnnotatedString {
                        append("Agenda${if (names.size > 1) "s" else ""} : ")
                        names.forEachIndexed { i, name ->
                            if (i > 0) append(" & ")
                            withStyle(SpanStyle(color = Color(agendaColors[name] ?: course.colorArgb))) { append(name) }
                        }
                    },
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(startInstant.format(dayFmt).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                Text("Heure : $timeStr", fontWeight = FontWeight.SemiBold)
                when {
                    isOngoing(course, nowEpoch) ->
                        Text("🔴 En cours · reste ${formatRemaining(course.endTimeEpoch - nowEpoch)}", color = Color.Red, fontWeight = FontWeight.Bold)
                    course.startTimeEpoch > nowEpoch && course.startTimeEpoch - nowEpoch < 24 * 3600 ->
                        Text("⏳ Commence dans ${formatRemaining(course.startTimeEpoch - nowEpoch)}", fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (course.location.isNotBlank()) {
                    Text("Salle :", fontWeight = FontWeight.Bold)
                    Text(course.location)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (cleanDesc.isNotBlank()) {
                    Text("Description :", fontWeight = FontWeight.Bold)
                    Text(cleanDesc, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            TextButton(onClick = { onHideSubject(course.title) }) {
                Text("Cacher cette matière", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
