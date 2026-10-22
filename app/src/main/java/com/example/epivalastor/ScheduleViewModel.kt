package com.example.epivalastor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// AndroidViewModel + "by viewModels()" : le ViewModel survit aux rotations d'écran
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScheduleRepository(AppDatabase.getDatabase(application), IcsManager())

    val courses = repository.allCoursesFlow
    val links = repository.allLinksFlow
    val changes = repository.allChangesFlow
    val unseenChangesCount = repository.unseenChangesCountFlow

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Passe à true quand une synchro échoue (pour afficher un message)
    private val _syncFailed = MutableStateFlow(false)
    val syncFailed: StateFlow<Boolean> = _syncFailed.asStateFlow()

    private val _lastSyncEpoch = MutableStateFlow(SyncManager.lastSyncEpoch(application))
    val lastSyncEpoch: StateFlow<Long> = _lastSyncEpoch.asStateFlow()

    init {
        // Synchro automatique à l'ouverture de l'app
        syncSchedule()
    }

    fun addLink(url: String, name: String, colorArgb: Int) {
        viewModelScope.launch {
            repository.addLink(url.trim(), name.trim(), colorArgb)
            syncSchedule(force = true)
        }
    }

    fun updateLink(oldLink: IcsLinkEntity, updatedLink: IcsLinkEntity) {
        viewModelScope.launch {
            repository.updateLink(oldLink, updatedLink.copy(url = updatedLink.url.trim(), name = updatedLink.name.trim()))
            syncSchedule(force = true)
        }
    }

    fun removeLink(link: IcsLinkEntity) {
        viewModelScope.launch {
            repository.removeLink(link)
            CourseNotifier.update(getApplication())
        }
    }

    fun markChangesSeen() {
        viewModelScope.launch { repository.markChangesSeen() }
    }

    fun consumeSyncError() {
        _syncFailed.value = false
    }

    /** force = true : lance la synchro même si une autre est en cours (elle attendra son tour). */
    private var runningSyncs = 0 // modifié uniquement sur le thread principal

    fun syncSchedule(force: Boolean = false) {
        if (runningSyncs > 0 && !force) return // Évite de relancer la même synchro
        runningSyncs++
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                val ok = SyncManager.sync(getApplication())
                if (!ok) _syncFailed.value = true
            } finally {
                runningSyncs--
                if (runningSyncs == 0) _isRefreshing.value = false
                _lastSyncEpoch.value = SyncManager.lastSyncEpoch(getApplication())
            }
        }
    }
}
