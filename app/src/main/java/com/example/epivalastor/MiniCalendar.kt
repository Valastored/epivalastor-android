package com.example.epivalastor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Mini-calendrier mensuel : un point de couleur par agenda les jours où il y a cours,
 * un point rouge les jours d'examen. Toucher un jour y emmène le planning.
 */
@Composable
fun MiniCalendarDialog(
    courses: List<CourseEntity>,
    accentColor: Color,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val today = LocalDate.now(PARIS_ZONE)
    var month by remember { mutableStateOf(YearMonth.from(today)) }

    // Pour chaque jour : couleurs des cours (max 3) + présence d'un examen
    val dayInfo = remember(courses) {
        courses.groupBy { courseDate(it) }.mapValues { (_, list) ->
            Triple(
                list.filterNot { isHoliday(it) }.map { it.colorArgb }.distinct().take(3),
                list.any { isExam(it) },
                list.any { isHoliday(it) }
            )
        }
    }
    val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
                    }
                    Text(
                        month.format(monthFmt).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    listOf("L", "M", "M", "J", "V", "S", "D").forEach { d ->
                        Text(
                            d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                val firstDay = month.atDay(1)
                val offset = firstDay.dayOfWeek.value - 1 // lundi = 0
                val cellCount = offset + month.lengthOfMonth()
                val rows = (cellCount + 6) / 7

                for (row in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val dayNumber = row * 7 + col - offset + 1
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (dayNumber in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(dayNumber)
                                    val info = dayInfo[date]
                                    val isToday = date == today
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .then(if (isToday) Modifier.border(2.dp, accentColor, CircleShape) else Modifier)
                                            .background(
                                                when {
                                                    info == null -> Color.Transparent
                                                    info.third -> HOLIDAY_COLOR.copy(alpha = 0.25f)
                                                    else -> accentColor.copy(alpha = 0.10f)
                                                }
                                            )
                                            .clickable { onDateSelected(date) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            dayNumber.toString(),
                                            fontSize = 13.sp,
                                            fontWeight = if (isToday || info != null) FontWeight.Bold else FontWeight.Normal,
                                            color = if (info == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                    else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (info != null) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                val (colors, hasExam, hasHoliday) = info
                                                if (hasExam) {
                                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(EXAM_RED))
                                                } else if (hasHoliday && colors.isEmpty()) {
                                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(HOLIDAY_COLOR))
                                                } else {
                                                    colors.forEach { c ->
                                                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Color(c)))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onDateSelected(today) }) { Text("Aujourd'hui") }
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }
            }
        }
    }
}
