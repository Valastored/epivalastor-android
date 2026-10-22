package com.example.epivalastor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Action « partager ce jour en image » (fournie par l'écran principal). */
val LocalShareDay = staticCompositionLocalOf<((LocalDate, List<CourseEntity>) -> Unit)?> { null }

/** true si l'app est affichée en thème sombre (choix système, clair ou sombre). */
val LocalIsDark = staticCompositionLocalOf { false }

val PARIS_ZONE: ZoneId = ZoneId.of("Europe/Paris")
val EXAM_RED = Color(0xFFD32F2F)
/** Couleur spéciale des jours fériés / vacances */
val HOLIDAY_COLOR = Color(0xFF00A6A6)

fun courseDate(c: CourseEntity): LocalDate =
    Instant.ofEpochSecond(c.startTimeEpoch).atZone(PARIS_ZONE).toLocalDate()

fun isOngoing(c: CourseEntity, nowEpoch: Long) = nowEpoch >= c.startTimeEpoch && nowEpoch < c.endTimeEpoch

fun courseProgress(c: CourseEntity, nowEpoch: Long): Float? {
    if (!isOngoing(c, nowEpoch)) return null
    val total = (c.endTimeEpoch - c.startTimeEpoch).toFloat()
    return if (total > 0f) ((nowEpoch - c.startTimeEpoch) / total).coerceIn(0f, 1f) else null
}

/** « 1 h 05 » ou « 23 min » */
fun formatRemaining(seconds: Long): String {
    val minutes = ((seconds + 59) / 60).coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
}

/**
 * Fait défiler la liste pour que l'élément `index` soit centré verticalement à l'écran
 * (ou collé en haut s'il est plus grand que l'écran).
 */
suspend fun centerItem(listState: LazyListState, index: Int) {
    fun viewport() = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    fun visible() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    var item = visible()
    if (item == null) {
        // Pas encore affiché : on s'en approche en visant le milieu avec une taille estimée
        val estimated = listState.layoutInfo.visibleItemsInfo.map { it.size }.maxOrNull() ?: 0
        listState.animateScrollToItem(index, scrollOffset = -((viewport() - estimated) / 2).coerceAtLeast(0))
        item = visible() ?: return
    }
    // Correction finale : centre exact d'après la vraie taille du bloc
    val desiredTop = ((viewport() - item.size) / 2).coerceAtLeast(0)
    val delta = (item.offset - desiredTop).toFloat()
    if (kotlin.math.abs(delta) > 1f) listState.animateScrollBy(delta)
}

/** Demande de défilement vers une date (mini-calendrier, recherche). */
data class JumpRequest(val date: LocalDate, val nonce: Long = System.nanoTime())

@Composable
private fun onAccentColor(): Color = if (LocalIsDark.current) Color(0xFF381E72) else Color.White

// ---------------- Vue semaine ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeekViewPager(
    visibleCourses: List<CourseEntity>,
    accentColor: Color,
    nowEpoch: Long,
    jumpRequest: JumpRequest?,
    onTitleClick: () -> Unit,
    onVisibleChange: (LocalDate) -> Unit = {},
    onCourseClick: (CourseEntity) -> Unit
) {
    val weekFormatter = DateTimeFormatter.ofPattern("dd.MM")
    val coursesByWeek = remember(visibleCourses) {
        visibleCourses.groupBy { courseDate(it).with(DayOfWeek.MONDAY) }.toSortedMap()
    }
    val weekKeys = remember(coursesByWeek) { coursesByWeek.keys.toList() }
    val today = LocalDate.now(PARIS_ZONE)
    val pagerState = rememberPagerState(pageCount = { weekKeys.size })
    val coroutineScope = rememberCoroutineScope()

    fun targetWeek(): LocalDate? = weekKeys.firstOrNull { wk ->
        coursesByWeek[wk].orEmpty().any { !courseDate(it).isBefore(today) }
    } ?: weekKeys.lastOrNull()

    // Demande de défilement vertical vers un jour précis, traitée une seule fois par la page concernée
    var dayScroll by remember { mutableStateOf<JumpRequest?>(null) }
    var handledDayScroll by remember { mutableStateOf<Long?>(null) }

    // Au lancement : va à la semaine en cours, puis centre le jour d'aujourd'hui
    // (ou le prochain jour de cours), comme le bouton « aujourd'hui »
    var lastScrolledWeek by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(weekKeys) {
        val target = targetWeek()
        if (target != null && target != lastScrolledWeek) {
            pagerState.scrollToPage(weekKeys.indexOf(target).coerceAtLeast(0))
            lastScrolledWeek = target
            dayScroll = JumpRequest(if (today.isAfter(target)) today else target)
        }
    }

    // Signale la semaine affichée (lundi) — sert au partage en image
    LaunchedEffect(pagerState.currentPage, weekKeys) {
        weekKeys.getOrNull(pagerState.currentPage)?.let(onVisibleChange)
    }


    // Saut vers la semaine d'une date choisie (ou la suivante qui a des cours), puis vers le jour
    LaunchedEffect(jumpRequest) {
        val req = jumpRequest ?: return@LaunchedEffect
        if (weekKeys.isEmpty()) return@LaunchedEffect
        val monday = req.date.with(DayOfWeek.MONDAY)
        val idx = weekKeys.indexOfFirst { !it.isBefore(monday) }.let { if (it == -1) weekKeys.lastIndex else it }
        pagerState.animateScrollToPage(idx)
        dayScroll = JumpRequest(if (req.date.isAfter(weekKeys[idx])) req.date else weekKeys[idx])
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val monday = weekKeys[page]
            val weeklyCourses = coursesByWeek[monday].orEmpty()
            val byDay = weeklyCourses.groupBy { courseDate(it) }.toSortedMap()
            val listState = rememberLazyListState()

            // Glisse jusqu'au jour demandé (aujourd'hui, ou le prochain jour de cours) s'il est dans cette semaine
            LaunchedEffect(dayScroll) {
                val req = dayScroll ?: return@LaunchedEffect
                if (handledDayScroll == req.nonce) return@LaunchedEffect
                if (req.date.isBefore(monday) || req.date.isAfter(monday.plusDays(6))) return@LaunchedEffect
                val dayIndex = byDay.keys.indexOfFirst { !it.isBefore(req.date) }
                if (dayIndex >= 0) centerItem(listState, 1 + dayIndex) // +1 : le titre de la semaine
                handledDayScroll = req.nonce
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item {
                    // Même hauteur que les boutons Menu/Réglages (16 dp du haut, 48 dp de haut) et marges
                    // symétriques : le titre est centré à l'écran et aligné avec le bouton.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp, start = 70.dp, end = 70.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTitleClick() }, // Touche le titre = mini-calendrier
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Semaine du ${monday.format(weekFormatter)} au ${monday.plusDays(6).format(weekFormatter)}",
                            style = MaterialTheme.typography.titleLarge,
                            color = accentColor,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                byDay.forEach { (date, dailyCourses) ->
                    item(key = date.toString()) {
                        DayBlock(date, dailyCourses, today, nowEpoch, accentColor, onCourseClick)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                val target = targetWeek()
                if (target != null) coroutineScope.launch {
                    pagerState.animateScrollToPage(weekKeys.indexOf(target))
                    // Puis glisse jusqu'à aujourd'hui (ou le prochain jour de cours de la semaine)
                    dayScroll = JumpRequest(if (today.isAfter(target)) today else target)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = accentColor
        ) {
            Icon(Icons.Default.Today, contentDescription = "Retour à la semaine actuelle", tint = onAccentColor())
        }
    }
}

// ---------------- Vue jour ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayViewPager(
    visibleCourses: List<CourseEntity>,
    accentColor: Color,
    nowEpoch: Long,
    jumpRequest: JumpRequest?,
    onVisibleChange: (LocalDate) -> Unit = {},
    onCourseClick: (CourseEntity) -> Unit
) {
    val coursesByDay = remember(visibleCourses) { visibleCourses.groupBy { courseDate(it) }.toSortedMap() }
    val allDays = remember(coursesByDay) { coursesByDay.keys.toList() }
    if (allDays.isEmpty()) return

    val today = LocalDate.now(PARIS_ZONE)
    val pagerState = rememberPagerState(pageCount = { allDays.size })
    val coroutineScope = rememberCoroutineScope()

    var lastScrolledTarget by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(allDays) {
        val target = allDays.firstOrNull { !it.isBefore(today) } ?: allDays.lastOrNull()
        if (target != null && target != lastScrolledTarget) {
            pagerState.scrollToPage(allDays.indexOf(target).coerceAtLeast(0))
            lastScrolledTarget = target
        }
    }

    LaunchedEffect(pagerState.currentPage, allDays) {
        allDays.getOrNull(pagerState.currentPage)?.let(onVisibleChange)
    }

    LaunchedEffect(jumpRequest) {
        val req = jumpRequest ?: return@LaunchedEffect
        val idx = allDays.indexOfFirst { !it.isBefore(req.date) }.let { if (it == -1) allDays.lastIndex else it }
        pagerState.animateScrollToPage(idx)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val date = allDays[page]
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                contentPadding = PaddingValues(top = 72.dp, bottom = 96.dp)
            ) {
                item { DayBlock(date, coursesByDay[date].orEmpty(), today, nowEpoch, accentColor, onCourseClick) }
            }
        }

        FloatingActionButton(
            onClick = {
                val idx = allDays.indexOfFirst { !it.isBefore(today) }
                if (idx != -1) coroutineScope.launch { pagerState.animateScrollToPage(idx) }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = accentColor
        ) {
            Icon(Icons.Default.Today, contentDescription = "Retour à aujourd'hui", tint = onAccentColor())
        }
    }
}

// ---------------- Bloc d'une journée ----------------

@Composable
fun DayBlock(
    date: LocalDate,
    courses: List<CourseEntity>,
    today: LocalDate,
    nowEpoch: Long,
    accentColor: Color,
    onCourseClick: (CourseEntity) -> Unit
) {
    val isToday = date == today
    val isPast = date.isBefore(today)
    val dark = LocalIsDark.current
    val shape = RoundedCornerShape(20.dp)
    val blockBg = if (dark) Color(0xFF141414) else Color(0xFFF7F4FB)
    val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
    val holidays = courses.filter { isHoliday(it) }
    val realCourses = courses.filterNot { isHoliday(it) }
    val dayStart = realCourses.minOfOrNull { it.startTimeEpoch }
    val dayEnd = realCourses.maxOfOrNull { it.endTimeEpoch }
    val hoursStr = if (dayStart != null && dayEnd != null) {
        "${Instant.ofEpochSecond(dayStart).atZone(PARIS_ZONE).format(hourFmt)} – ${Instant.ofEpochSecond(dayEnd).atZone(PARIS_ZONE).format(hourFmt)}"
    } else ""
    val headerBg = when {
        isToday -> accentColor.copy(alpha = 0.20f)
        holidays.isNotEmpty() -> HOLIDAY_COLOR.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .alpha(if (isPast) 0.55f else 1f)
            .then(
                if (isToday) Modifier.border(2.dp, accentColor, shape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = blockBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE)).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) accentColor else MaterialTheme.colorScheme.onSurface
                    )
                    if (hoursStr.isNotEmpty()) {
                        Text(
                            text = "  ·  $hoursStr",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.FRANCE)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (isToday) {
                Surface(shape = RoundedCornerShape(50), color = accentColor) {
                    Text(
                        "Aujourd'hui",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = onAccentColor()
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (holidays.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(50), color = HOLIDAY_COLOR) {
                    Text(
                        holidayLabel(holidays.first()),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (realCourses.any { isExam(it) }) {
                Surface(shape = RoundedCornerShape(50), color = EXAM_RED) {
                    Text(
                        "📝 Examen",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (realCourses.isNotEmpty()) {
                Text(
                    text = "${realCourses.size} cours",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            LocalShareDay.current?.let { share ->
                IconButton(onClick = { share(date, courses) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Share, contentDescription = "Partager ce jour",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            // Fériés / vacances en premier, hors calcul des pauses
            holidays.forEach { h ->
                StylishCourseCard(course = h, nowEpoch = nowEpoch, onClick = { onCourseClick(h) })
            }
            calculateBreaks(realCourses).forEach { item ->
                when (item) {
                    is CourseEntity -> StylishCourseCard(
                        course = item,
                        nowEpoch = nowEpoch,
                        onClick = { onCourseClick(item) }
                    )
                    is BreakEntity -> BreakCard(breakEntity = item)
                }
            }
        }
    }
}

// ---------------- Pauses ----------------

fun calculateBreaks(courses: List<CourseEntity>): List<Any> {
    val result = mutableListOf<Any>()
    val sorted = courses.sortedBy { it.startTimeEpoch }

    for (i in sorted.indices) {
        val current = sorted[i]
        result.add(current)

        if (i < sorted.size - 1) {
            val next = sorted[i + 1]
            val currentEnd = Instant.ofEpochSecond(current.endTimeEpoch).atZone(PARIS_ZONE)
            val nextStart = Instant.ofEpochSecond(next.startTimeEpoch).atZone(PARIS_ZONE)

            if (currentEnd.toLocalDate() == nextStart.toLocalDate() && currentEnd.isBefore(nextStart)) {
                val minutes = ChronoUnit.MINUTES.between(currentEnd, nextStart)
                if (minutes >= 30) {
                    val hours = minutes / 60
                    val mins = minutes % 60
                    val durationStr = if (hours > 0) "$hours h ${if (mins > 0) "$mins min" else ""}".trim() else "$mins min"
                    // La pause commence entre 11h00 et 13h59 = « Midi »
                    val type = when {
                        currentEnd.hour in 11..13 -> "Midi"
                        currentEnd.hour >= 18 -> "du soir"
                        else -> "Inter-cours"
                    }
                    result.add(BreakEntity(type = type, duration = durationStr))
                }
            }
        }
    }
    return result
}

data class BreakEntity(val type: String, val duration: String)

@Composable
fun BreakCard(breakEntity: BreakEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.3f))
        Text(
            text = "  Pause ${breakEntity.type} · ${breakEntity.duration}  ",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            fontWeight = FontWeight.Medium
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.3f))
    }
}

// ---------------- Carte de cours ----------------

@Composable
fun StylishCourseCard(course: CourseEntity, nowEpoch: Long, onClick: () -> Unit) {
    val startInstant = Instant.ofEpochSecond(course.startTimeEpoch).atZone(PARIS_ZONE)
    val endInstant = Instant.ofEpochSecond(course.endTimeEpoch).atZone(PARIS_ZONE)
    val startTimeStr = startInstant.format(DateTimeFormatter.ofPattern("HH:mm"))
    val endTimeStr = endInstant.format(DateTimeFormatter.ofPattern("HH:mm"))

    val holiday = isHoliday(course)
    // Pas de « en cours » ni de ligne rouge pour un férié / des vacances
    val ongoing = !holiday && isOngoing(course, nowEpoch)
    val progress = if (holiday) null else courseProgress(course, nowEpoch)
    val allDay = course.endTimeEpoch - course.startTimeEpoch >= 20 * 3600

    val exam = isExam(course)
    val teacher = courseTeacher(course)

    val maxLength = 35
    val truncatedLocation = if (course.location.length > maxLength) course.location.take(maxLength) + "..." else course.location

    val cardBaseColor = Color(course.colorArgb)
    val dark = LocalIsDark.current
    val containerBg = when {
        holiday -> HOLIDAY_COLOR.copy(alpha = if (dark) 0.28f else 0.16f)
        exam -> EXAM_RED.copy(alpha = if (dark) 0.25f else 0.12f)
        dark -> cardBaseColor.copy(alpha = 0.22f)
        else -> cardBaseColor.copy(alpha = 0.15f)
    }
    val borderColor = when {
        holiday -> HOLIDAY_COLOR
        exam -> EXAM_RED
        ongoing -> Color.Red.copy(alpha = 0.8f)
        else -> cardBaseColor.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .border(if (ongoing || exam || holiday) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.width(76.dp).fillMaxHeight(),
                    color = (if (holiday) HOLIDAY_COLOR else cardBaseColor).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (holiday && allDay) {
                            Text(text = "Toute la", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = "journée", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        } else {
                            Text(text = startTimeStr, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = endTimeStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = course.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (holiday) {
                            Surface(shape = RoundedCornerShape(50.dp), color = HOLIDAY_COLOR) {
                                Text(
                                    text = holidayLabel(course),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (exam && !ongoing) {
                            Surface(shape = RoundedCornerShape(50.dp), color = EXAM_RED) {
                                Text(
                                    text = "📝 Examen",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (ongoing) {
                            Surface(shape = RoundedCornerShape(50.dp), color = Color.Red.copy(alpha = 0.85f)) {
                                Text(
                                    // Temps restant toujours visible sur la carte du cours en cours
                                    text = "🔴 ${formatRemaining(course.endTimeEpoch - nowEpoch)}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val infoText = buildString {
                        if (truncatedLocation.isNotBlank() && truncatedLocation != "Non spécifié") append("📍 $truncatedLocation")
                        if (teacher.isNotBlank()) {
                            if (isNotEmpty()) append("  |  ")
                            append("👤 $teacher")
                        }
                    }
                    if (infoText.isNotBlank()) {
                        Text(text = infoText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    }
                }
            }

            if (progress != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val y = size.height * progress
                    drawLine(
                        color = Color.Red.copy(alpha = 0.7f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawCircle(color = Color.Red, radius = 4.dp.toPx(), center = Offset(0f, y))
                }
            }
        }
    }
}


// ---------------- Indicateur « prochain cours » ----------------

/**
 * Pastille toujours visible : cours en cours (temps restant) ou prochain cours (dans combien de temps).
 * Ignore les fériés / vacances. Rien n'est affiché s'il n'y a plus de cours à venir.
 */
@Composable
fun NextCourseBanner(courses: List<CourseEntity>, nowEpoch: Long, modifier: Modifier = Modifier, onClick: (CourseEntity) -> Unit) {
    val real = remember(courses) { courses.filterNot { isHoliday(it) }.sortedBy { it.startTimeEpoch } }
    val current = real.firstOrNull { isOngoing(it, nowEpoch) }
    val upcoming = real.firstOrNull { it.startTimeEpoch > nowEpoch }
    val shown = current ?: upcoming ?: return

    val today = LocalDate.now(PARIS_ZONE)
    val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
    val start = Instant.ofEpochSecond(shown.startTimeEpoch).atZone(PARIS_ZONE)
    val exam = isExam(shown)

    val (label, labelColor) = when {
        current != null -> "🔴 En cours · reste ${formatRemaining(shown.endTimeEpoch - nowEpoch)}" to Color.Red
        shown.startTimeEpoch - nowEpoch <= 12 * 3600 -> "⏳ Dans ${formatRemaining(shown.startTimeEpoch - nowEpoch)}" to MaterialTheme.colorScheme.primary
        start.toLocalDate() == today.plusDays(1) -> "📅 Demain à ${start.format(hourFmt)}" to MaterialTheme.colorScheme.primary
        else -> "📅 ${start.format(DateTimeFormatter.ofPattern("EEE dd/MM à HH:mm", Locale.FRANCE))}" to MaterialTheme.colorScheme.primary
    }
    val location = if (shown.location.isBlank() || shown.location == "Non spécifié") "" else " · 📍 ${shown.location}"

    Surface(
        modifier = modifier.clickable { onClick(shown) },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (exam) EXAM_RED else Color(shown.colorArgb))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                (if (exam) "📝 " else "") + shown.title + location,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
