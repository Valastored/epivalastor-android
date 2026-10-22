package com.example.epivalastor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SettingsPage(val title: String) {
    MAIN("Réglages"),
    APPEARANCE("Apparence"),
    CALENDARS("Agendas"),
    NOTIFICATIONS("Notifications"),
    HIDDEN("Matières masquées"),
    SYNC("Synchronisation"),
    ABOUT("À propos")
}

/** Écran de réglages plein écran, avec plusieurs pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScheduleViewModel,
    settings: AppSettings,
    initialPage: SettingsPage,
    onClose: () -> Unit
) {
    var page by remember { mutableStateOf(initialPage) }

    fun goBack() {
        if (page == SettingsPage.MAIN) onClose() else { page = SettingsPage.MAIN }
    }
    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(
                            if (page == SettingsPage.MAIN) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState != SettingsPage.MAIN) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "settings_pages",
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { current ->
            when (current) {
                SettingsPage.MAIN -> MainSettingsPage(viewModel, settings) { page = it }
                SettingsPage.APPEARANCE -> AppearancePage(settings)
                SettingsPage.CALENDARS -> CalendarsPage(viewModel)
                SettingsPage.NOTIFICATIONS -> NotificationsPage(settings)
                SettingsPage.HIDDEN -> HiddenSubjectsPage(settings)
                SettingsPage.SYNC -> SyncPage(viewModel)
                SettingsPage.ABOUT -> AboutPage()
            }
        }
    }
}

// ---------------- Composants communs ----------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }
    )
}

@Composable
private fun RadioRow(label: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable { onClick() }
    )
}

// ---------------- Page principale ----------------

@Composable
private fun MainSettingsPage(viewModel: ScheduleViewModel, settings: AppSettings, onOpen: (SettingsPage) -> Unit) {
    val links by viewModel.links.collectAsState(initial = emptyList())
    val themeLabel = when (settings.themeMode) {
        AppSettings.THEME_LIGHT -> "clair"
        AppSettings.THEME_DARK -> "sombre"
        else -> "système"
    }
    val hiddenCount = settings.hiddenSubjects.size

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SettingsEntry(Icons.Default.Palette, "Apparence",
                "Thème $themeLabel · vue ${if (settings.viewMode == "week") "semaine" else "jour"}") { onOpen(SettingsPage.APPEARANCE) }
        }
        item {
            SettingsEntry(Icons.Default.CalendarMonth, "Agendas",
                if (links.isEmpty()) "Aucun agenda ICS" else "${links.size} agenda${if (links.size > 1) "s" else ""} ICS") { onOpen(SettingsPage.CALENDARS) }
        }
        item {
            SettingsEntry(Icons.Default.Notifications, "Notifications",
                "Cours en direct, rappels, changements") { onOpen(SettingsPage.NOTIFICATIONS) }
        }
        item {
            SettingsEntry(Icons.Default.VisibilityOff, "Matières masquées",
                if (hiddenCount == 0) "Aucune" else "$hiddenCount matière${if (hiddenCount > 1) "s" else ""}") { onOpen(SettingsPage.HIDDEN) }
        }
        item {
            SettingsEntry(Icons.Default.Sync, "Synchronisation",
                "Automatique chaque jour vers 6h") { onOpen(SettingsPage.SYNC) }
        }
        item {
            SettingsEntry(Icons.Default.Info, "À propos", "EpiValastor, par Valastor") { onOpen(SettingsPage.ABOUT) }
        }
    }
}

// ---------------- Apparence ----------------

@Composable
private fun AppearancePage(settings: AppSettings) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SectionTitle("Thème") }
        item {
            RadioRow("Système", "Suit le réglage du téléphone", settings.themeMode == AppSettings.THEME_SYSTEM) {
                settings.themeMode = AppSettings.THEME_SYSTEM
            }
        }
        item { RadioRow("Clair", selected = settings.themeMode == AppSettings.THEME_LIGHT) { settings.themeMode = AppSettings.THEME_LIGHT } }
        item { RadioRow("Sombre", selected = settings.themeMode == AppSettings.THEME_DARK) { settings.themeMode = AppSettings.THEME_DARK } }

        item { SectionTitle("Affichage du planning") }
        item {
            SwitchRow("Prochain cours", "Pastille en bas de l'écran avec le cours en cours ou le prochain", settings.nextBanner) {
                settings.nextBanner = it
            }
        }
        item { RadioRow("Semaine", "Une page par semaine, un bloc par jour", settings.viewMode == "week") { settings.viewMode = "week" } }
        item { RadioRow("Jour", "Une page par jour", settings.viewMode == "day") { settings.viewMode = "day" } }
    }
}

// ---------------- Agendas ----------------

@Composable
private fun CalendarsPage(viewModel: ScheduleViewModel) {
    val links by viewModel.links.collectAsState(initial = emptyList())
    var newLinkUrl by remember { mutableStateOf("") }
    var newLinkName by remember { mutableStateOf("") }
    val availableColors = listOf(Color(0xFFEA4335), Color(0xFF4285F4), Color(0xFFFF9800), Color(0xFF34A853), Color(0xFF9C27B0), Color(0xFF00ACC1))
    var selectedColor by remember { mutableStateOf(availableColors[0]) }
    var linkBeingEdited by remember { mutableStateOf<IcsLinkEntity?>(null) }

    fun resetForm() {
        linkBeingEdited = null
        newLinkName = ""
        newLinkUrl = ""
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                if (linkBeingEdited != null) "Modifier l'agenda" else "Ajouter un agenda",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = newLinkName, onValueChange = { newLinkName = it },
                label = { Text("Nom (ex: EPITA, GDG)") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true
            )
            OutlinedTextField(
                value = newLinkUrl, onValueChange = { newLinkUrl = it },
                label = { Text("Coller le lien ICS") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("Couleur de l'agenda :", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                availableColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (selectedColor == color) 3.dp else 0.dp,
                                color = if (selectedColor == color) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { selectedColor = color }
                    )
                }
            }

            val formValid = newLinkUrl.isNotBlank() && newLinkName.isNotBlank()
            if (linkBeingEdited != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { resetForm() }, modifier = Modifier.weight(1f)) { Text("Annuler") }
                    Button(
                        enabled = formValid,
                        onClick = {
                            linkBeingEdited?.let { edited ->
                                viewModel.updateLink(edited, edited.copy(name = newLinkName, url = newLinkUrl, colorArgb = selectedColor.toArgb()))
                            }
                            resetForm()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Sauvegarder") }
                }
            } else {
                Button(
                    enabled = formValid,
                    onClick = {
                        viewModel.addLink(newLinkUrl, newLinkName, selectedColor.toArgb())
                        resetForm()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Ajouter le lien") }
            }
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Text("Agendas actifs", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            if (links.isEmpty()) {
                Text("Aucun agenda pour le moment.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }

        items(links, key = { it.id }) { link ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(link.colorArgb)))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(link.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(link.url, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                IconButton(onClick = {
                    linkBeingEdited = link
                    newLinkName = link.name
                    newLinkUrl = link.url
                    selectedColor = Color(link.colorArgb)
                }) { Icon(Icons.Default.Edit, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { viewModel.removeLink(link) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ---------------- Notifications ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsPage(settings: AppSettings) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        CourseNotifier.update(context)
    }

    val liveUpdateSupported = Build.VERSION.SDK_INT >= 36
    val promotedAllowed = if (liveUpdateSupported) canPostPromoted(context) else false
    val liveSubtitle = when {
        !liveUpdateSupported -> "Nécessite Android 16 ou plus récent"
        !promotedAllowed -> "Bloqué par Android : active « Live Updates » dans les réglages de notifications ci-dessous"
        else -> "Pastille dans la barre d'état avec le temps restant, visible en permanence"
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (!permissionGranted) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Les notifications sont bloquées", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Sans cette autorisation, aucune alerte ne peut s'afficher.", color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Autoriser") }
                    }
                }
            }
        }

        item { SectionTitle("Pendant un cours") }
        item {
            SwitchRow("Cours en cours", "Progression et compte à rebours, mis à jour chaque minute", settings.ongoingNotif) {
                settings.ongoingNotif = it
            }
        }
        item {
            SwitchRow(
                "Live Update",
                liveSubtitle,
                checked = settings.liveUpdate && liveUpdateSupported,
                enabled = liveUpdateSupported && settings.ongoingNotif
            ) { settings.liveUpdate = it }
        }

        item { SectionTitle("Avant un cours") }
        item {
            SwitchRow("Rappel ${settings.reminderMinutes} min avant", "Retiré automatiquement quand le cours commence", settings.upcomingNotif) {
                settings.upcomingNotif = it
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppSettings.REMINDER_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = settings.reminderMinutes == minutes,
                        enabled = settings.upcomingNotif,
                        onClick = { settings.reminderMinutes = minutes },
                        label = { Text(if (minutes == 60) "1 h" else "$minutes min") }
                    )
                }
            }
        }

        item { SectionTitle("Examens") }
        item {
            SwitchRow("Rappel la veille", "Notification à 19h la veille d'un partiel, QCM, contrôle, soutenance…", settings.examNotif) {
                settings.examNotif = it
            }
        }

        item { SectionTitle("Emploi du temps") }
        item {
            SwitchRow("Changements", "Alerte quand un cours est ajouté, annulé ou déplacé", settings.changeNotif) {
                settings.changeNotif = it
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("Réglages des notifications Android") }
        }
    }
}

private fun canPostPromoted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.canPostPromotedNotifications()
}

// ---------------- Matières masquées ----------------

@Composable
private fun HiddenSubjectsPage(settings: AppSettings) {
    val hidden = settings.hiddenSubjects.sorted()
    if (hidden.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Aucune matière masquée.\nTouche un cours puis « Cacher cette matière » pour le masquer.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(hidden) { subject ->
            ListItem(
                headlineContent = { Text(subject) },
                trailingContent = {
                    IconButton(onClick = { settings.hiddenSubjects = settings.hiddenSubjects - subject }) {
                        Icon(Icons.Default.Visibility, contentDescription = "Réafficher", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    }
}

// ---------------- Synchronisation ----------------

@Composable
private fun SyncPage(viewModel: ScheduleViewModel) {
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lastSync by viewModel.lastSyncEpoch.collectAsState()
    val lastSyncStr = if (lastSync == 0L) "jamais"
        else Instant.ofEpochSecond(lastSync).atZone(PARIS_ZONE)
            .format(DateTimeFormatter.ofPattern("EEEE dd MMMM à HH:mm", Locale.FRANCE))

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        ListItem(
            headlineContent = { Text("Dernière synchronisation") },
            supportingContent = { Text(lastSyncStr) },
            leadingContent = { Icon(Icons.Default.History, contentDescription = null) }
        )
        ListItem(
            headlineContent = { Text("Synchronisation automatique") },
            supportingContent = { Text("Tous les jours vers 6h, dès que le réseau est disponible. Aussi à chaque ouverture de l'app.") },
            leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.syncSchedule() },
            enabled = !isRefreshing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Synchronisation…")
            } else {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Synchroniser maintenant")
            }
        }
    }
}

// ---------------- À propos ----------------

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                Icons.Default.CalendarMonth, contentDescription = null,
                modifier = Modifier.padding(20.dp).size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("EpiValastor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Version $version", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Développé par Valastor", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(context.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}
