package com.example.epivalastor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SearchField(val label: String) {
    ALL("Tout"), SUBJECT("Matière"), TEACHER("Prof"), ROOM("Salle")
}

/** Recherche dans tous les cours, par matière, prof ou salle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    courses: List<CourseEntity>,
    nowEpoch: Long,
    onCourseClick: (CourseEntity) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    var query by remember { mutableStateOf("") }
    var field by remember { mutableStateOf(SearchField.ALL) }
    var examsOnly by remember { mutableStateOf(false) }
    var includePast by remember { mutableStateOf(false) }

    val normalizedQuery = normalizeForSearch(query.trim())
    val results = remember(courses, normalizedQuery, field, examsOnly, includePast, nowEpoch / 600) {
        courses.asSequence()
            .filter { includePast || it.endTimeEpoch > nowEpoch }
            .filter { !examsOnly || isExam(it) }
            .filter { c ->
                if (normalizedQuery.isEmpty()) return@filter true
                val subject = normalizeForSearch(c.title)
                val teacher = normalizeForSearch(courseTeacher(c))
                val room = normalizeForSearch(c.location)
                when (field) {
                    SearchField.ALL -> normalizedQuery in subject || normalizedQuery in teacher || normalizedQuery in room
                    SearchField.SUBJECT -> normalizedQuery in subject
                    SearchField.TEACHER -> normalizedQuery in teacher
                    SearchField.ROOM -> normalizedQuery in room
                }
            }
            .sortedBy { it.startTimeEpoch }
            .take(300)
            .toList()
    }
    val searching = normalizedQuery.isNotEmpty() || examsOnly

    // Suggestions quand rien n'est tapé : les matières, par nombre de cours à venir
    val subjects = remember(courses, nowEpoch / 3600) {
        courses.filter { it.endTimeEpoch > nowEpoch }
            .groupingBy { it.title }.eachCount()
            .entries.sortedByDescending { it.value }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rechercher", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Matière, prof ou salle…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Effacer") }
                    }
                },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchField.entries.forEach { f ->
                    FilterChip(selected = field == f, onClick = { field = f }, label = { Text(f.label) })
                }
                FilterChip(selected = examsOnly, onClick = { examsOnly = !examsOnly }, label = { Text("📝 Examens") })
                FilterChip(selected = includePast, onClick = { includePast = !includePast }, label = { Text("Cours passés") })
            }

            if (!searching) {
                Text(
                    "Matières à venir",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(subjects, key = { it.key }) { (subject, count) ->
                        ListItem(
                            headlineContent = { Text(subject) },
                            trailingContent = { Text("$count cours") },
                            modifier = Modifier.clickable {
                                query = subject
                                field = SearchField.SUBJECT
                            }
                        )
                    }
                }
            } else {
                val dayFmt = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy", Locale.FRANCE)
                Text(
                    if (results.isEmpty()) "Aucun résultat" else "${results.size}${if (results.size == 300) "+" else ""} résultat${if (results.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    val byDay = results.groupBy { courseDate(it) }
                    byDay.forEach { (date, dayCourses) ->
                        item(key = "h_$date") {
                            Text(
                                date.format(dayFmt).replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(dayCourses, key = { "c_${it.id}_${it.startTimeEpoch}" }) { c ->
                            StylishCourseCard(course = c, nowEpoch = nowEpoch, onClick = { onCourseClick(c) })
                        }
                    }
                }
            }
        }
    }
}
