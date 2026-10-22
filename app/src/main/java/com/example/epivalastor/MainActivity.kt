package com.example.epivalastor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/** Action demandée de l'extérieur (raccourci de l'icône, notification). */
data class UiAction(val name: String, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    // Le ViewModel survit aux rotations d'écran
    private val viewModel: ScheduleViewModel by viewModels()

    // Dernière action reçue (raccourci « Aujourd'hui », notification « changements »...)
    private var uiAction by mutableStateOf<UiAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Synchro automatique quotidienne (vers 6h)
        DailySyncWorker.schedule(this)
        handleIntent(intent)

        val settings = AppSettings(this)

        setContent {
            val darkTheme = when (settings.themeMode) {
                AppSettings.THEME_DARK -> true
                AppSettings.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(LocalIsDark provides darkTheme) {
                MaterialTheme(
                    colorScheme = if (darkTheme) darkColorScheme(background = Color.Black, surface = Color(0xFF1E1E1E))
                                  else lightColorScheme()
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ScheduleScreen(viewModel = viewModel, settings = settings, uiAction = uiAction)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Reprogramme les notifications (au cas où l'app a été arrêtée de force)
        CourseNotifier.update(this)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = when {
            intent.getBooleanExtra(EXTRA_OPEN_CHANGES, false) -> "changes"
            else -> intent.getStringExtra(EXTRA_UI_ACTION)
        } ?: return
        uiAction = UiAction(action)
        intent.removeExtra(EXTRA_OPEN_CHANGES)
        intent.removeExtra(EXTRA_UI_ACTION)
    }

    companion object {
        const val EXTRA_OPEN_CHANGES = "open_changes"
        const val EXTRA_UI_ACTION = "ui_action" // valeurs : today, search, sync, stats, changes
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel, settings: AppSettings, uiAction: UiAction?) {
    val courses by viewModel.courses.collectAsState(initial = emptyList())
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val syncFailed by viewModel.syncFailed.collectAsState()
    val changes by viewModel.changes.collectAsState(initial = emptyList())
    val unseenChangesCount by viewModel.unseenChangesCount.collectAsState(initial = 0)
    val links by viewModel.links.collectAsState(initial = emptyList())
    val agendaColors = remember(links) { links.associate { it.name to it.colorArgb } }

    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var showChangesDialog by remember { mutableStateOf(false) }
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var jumpRequest by remember { mutableStateOf<JumpRequest?>(null) }
    var visibleAnchor by remember { mutableStateOf<java.time.LocalDate?>(null) } // lundi (semaine) ou jour affiché
    val context = LocalContext.current

    // Un changement de vue (semaine/jour) ne doit pas rejouer l'ancien saut de date
    LaunchedEffect(settings.viewMode) { jumpRequest = null }

    // Raccourcis de l'icône et notification « changements »
    LaunchedEffect(uiAction) {
        val action = uiAction ?: return@LaunchedEffect
        settingsPage = null
        showSearch = false
        showStats = false
        showCalendar = false
        when (action.name) {
            "changes" -> showChangesDialog = true
            "today" -> jumpRequest = JumpRequest(java.time.LocalDate.now(PARIS_ZONE))
            "search" -> showSearch = true
            "stats" -> showStats = true
            "sync" -> {
                Toast.makeText(context, "Synchronisation…", Toast.LENGTH_SHORT).show()
                viewModel.syncSchedule()
            }
        }
    }

    LaunchedEffect(syncFailed) {
        if (syncFailed) {
            Toast.makeText(context, "Synchronisation impossible : les cours déjà enregistrés sont conservés.", Toast.LENGTH_LONG).show()
            viewModel.consumeSyncError()
        }
    }

    // Horloge de l'écran (ligne rouge, temps restant)
    var nowEpoch by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            nowEpoch = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }

    val darkTheme = LocalIsDark.current
    // Image de planning en attente : on demande « Partager » ou « Enregistrer »
    var pendingImage by remember { mutableStateOf<PlanningImage?>(null) }
    var imageToSaveAfterPermission by remember { mutableStateOf<PlanningImage?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val img = imageToSaveAfterPermission
        imageToSaveAfterPermission = null
        if (granted && img != null) PlanningShare.saveToGallery(context, img, darkTheme)
        else if (!granted) Toast.makeText(context, "Autorisation de stockage refusée", Toast.LENGTH_SHORT).show()
    }
    val shareDay: (java.time.LocalDate, List<CourseEntity>) -> Unit = { date, list ->
        pendingImage = PlanningShare.dayImage(date, list)
    }

    pendingImage?.let { img ->
        PlanningImageDialog(
            image = img,
            onShare = {
                pendingImage = null
                PlanningShare.share(context, img, darkTheme)
            },
            onSave = {
                pendingImage = null
                val needsPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    imageToSaveAfterPermission = img
                    storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    PlanningShare.saveToGallery(context, img, darkTheme)
                }
            },
            onDismiss = { pendingImage = null }
        )
    }
    val buttonBgColor = if (darkTheme) Color(0xFFD0BCFF) else Color(0xFF9C27B0)
    val buttonTextColor = if (darkTheme) Color(0xFF381E72) else Color.White

    val uniqueCourses = remember(courses) {
        courses.groupBy { it.title to it.startTimeEpoch }.map { (_, group) ->
            if (group.size == 1) group.first()
            else group.first().copy(icsName = group.map { it.icsName }.distinct().joinToString(" & "))
        }
    }
    val hiddenSubjects = settings.hiddenSubjects
    val visibleCourses = remember(uniqueCourses, hiddenSubjects) {
        uniqueCourses.filter { it.title !in hiddenSubjects }
    }

    val pullToRefreshState = rememberPullToRefreshState()
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(true) { viewModel.syncSchedule() }
    }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) pullToRefreshState.startRefresh() else pullToRefreshState.endRefresh()
    }

    if (showChangesDialog) {
        ChangesDialog(
            changes = changes,
            onDismiss = { showChangesDialog = false },
            onMarkAllSeen = { viewModel.markChangesSeen() },
            onChangeClick = { change ->
                val course = visibleCourses.firstOrNull {
                    it.title == change.title && it.startTimeEpoch == change.newStartEpoch
                }
                if (course != null) {
                    showChangesDialog = false
                    selectedCourse = course
                }
            }
        )
    }

    if (showCalendar) {
        MiniCalendarDialog(
            courses = visibleCourses,
            accentColor = buttonBgColor,
            onDateSelected = { date ->
                showCalendar = false
                jumpRequest = JumpRequest(date)
            },
            onDismiss = { showCalendar = false }
        )
    }

    selectedCourse?.let { course ->
        CourseDetailsDialog(
            course = course,
            nowEpoch = nowEpoch,
            agendaColors = agendaColors,
            onHideSubject = {
                settings.hiddenSubjects = settings.hiddenSubjects + it
                selectedCourse = null
            },
            onDismiss = { selectedCourse = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            if (visibleCourses.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Aucun cours pour le moment.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { settingsPage = SettingsPage.CALENDARS },
                        colors = ButtonDefaults.buttonColors(containerColor = buttonBgColor)
                    ) {
                        Text("Ajouter un lien ICS", color = buttonTextColor)
                    }
                }
            } else {
                CompositionLocalProvider(LocalShareDay provides shareDay) {
                    if (settings.viewMode == "week") {
                        WeekViewPager(
                            visibleCourses, buttonBgColor, nowEpoch, jumpRequest,
                            onTitleClick = { showCalendar = true },
                            onVisibleChange = { visibleAnchor = it }
                        ) { selectedCourse = it }
                    } else {
                        DayViewPager(
                            visibleCourses, buttonBgColor, nowEpoch, jumpRequest,
                            onVisibleChange = { visibleAnchor = it }
                        ) { selectedCourse = it }
                    }
                }
            }

            // Menu en haut à gauche : recherche, mini-calendrier, statistiques
            Box(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = buttonBgColor)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rechercher") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = { showMenu = false; showSearch = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Mini-calendrier") },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        onClick = { showMenu = false; showCalendar = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Statistiques") },
                        leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                        onClick = { showMenu = false; showStats = true }
                    )
                    DropdownMenuItem(
                        text = { Text(if (settings.viewMode == "week") "Image de la semaine" else "Image du jour") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        enabled = visibleAnchor != null && visibleCourses.isNotEmpty(),
                        onClick = {
                            showMenu = false
                            val anchor = visibleAnchor ?: return@DropdownMenuItem
                            if (settings.viewMode == "week") {
                                val img = PlanningShare.weekImage(anchor, visibleCourses)
                                if (img == null) Toast.makeText(context, "Aucun cours cette semaine", Toast.LENGTH_SHORT).show()
                                else pendingImage = img
                            } else {
                                pendingImage = PlanningShare.dayImage(anchor, visibleCourses.filter { courseDate(it) == anchor })
                            }
                        }
                    )
                }
            }

            IconButton(
                onClick = { settingsPage = SettingsPage.MAIN },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Réglages", tint = buttonBgColor)
            }

            // Indicateur « prochain cours », toujours visible entre les deux boutons du bas
            if (settings.nextBanner && visibleCourses.isNotEmpty()) {
                NextCourseBanner(
                    courses = visibleCourses,
                    nowEpoch = nowEpoch,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 84.dp, end = 84.dp, bottom = 22.dp)
                ) { selectedCourse = it }
            }

            FloatingActionButton(
                onClick = { showChangesDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                BadgedBox(badge = {
                    if (unseenChangesCount > 0) {
                        Badge { Text(if (unseenChangesCount > 99) "99+" else unseenChangesCount.toString()) }
                    }
                }) {
                    Icon(Icons.Default.Notifications, contentDescription = "Changements récents", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            PullToRefreshContainer(
                state = pullToRefreshState,
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                    alpha = if (pullToRefreshState.isRefreshing || pullToRefreshState.progress > 0f) 1f else 0f
                }
            )
        }

        if (showSearch) {
            SearchScreen(
                courses = uniqueCourses,
                nowEpoch = nowEpoch,
                onCourseClick = { c ->
                    // On ferme la recherche, on amène le planning au bon jour et on ouvre les détails
                    showSearch = false
                    jumpRequest = JumpRequest(courseDate(c))
                    selectedCourse = c
                },
                onClose = { showSearch = false }
            )
        }

        if (showStats) {
            StatsScreen(courses = visibleCourses, nowEpoch = nowEpoch, onClose = { showStats = false })
        }

        // Écran de réglages plein écran, par-dessus le planning
        settingsPage?.let { page ->
            SettingsScreen(
                viewModel = viewModel,
                settings = settings,
                initialPage = page,
                onClose = { settingsPage = null }
            )
        }
    }
}
