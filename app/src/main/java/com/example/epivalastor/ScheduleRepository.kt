package com.example.epivalastor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Résultat d'une synchro : succès global, au moins un agenda OK, et les nouveaux changements détectés. */
data class SyncResult(val allOk: Boolean, val anySuccess: Boolean, val newChanges: List<ChangeEntity>)

class ScheduleRepository(
    private val db: AppDatabase,
    private val icsManager: IcsManager
) {
    private val courseDao = db.courseDao()
    private val icsLinkDao = db.icsLinkDao()
    private val changeDao = db.changeDao()

    val allCoursesFlow = courseDao.getAllCourses()
    val allLinksFlow = icsLinkDao.getAllLinksFlow()
    val allChangesFlow = changeDao.getAllChangesFlow()
    val unseenChangesCountFlow = changeDao.getUnseenCountFlow()

    suspend fun addLink(url: String, name: String, colorArgb: Int) {
        withContext(Dispatchers.IO) {
            icsLinkDao.insertLink(IcsLinkEntity(url = url, name = name, colorArgb = colorArgb))
        }
    }

    suspend fun updateLink(oldLink: IcsLinkEntity, updatedLink: IcsLinkEntity) {
        withContext(Dispatchers.IO) {
            val nowEpoch = System.currentTimeMillis() / 1000
            db.runInTransaction(Runnable {
                // Nouvelle URL = autre agenda : on repart de zéro pour ne pas inventer de faux « changements »
                if (oldLink.url != updatedLink.url) {
                    courseDao.removeFutureCourses(oldLink.name, nowEpoch)
                }
                // Met à jour l'historique complet (passé et futur) avec la nouvelle couleur/nom
                courseDao.updateCourseInfo(oldLink.name, updatedLink.name, updatedLink.colorArgb)
                changeDao.updateInfo(oldLink.name, updatedLink.name, updatedLink.colorArgb)
                // La clé est l'id : la mise à jour fonctionne même si l'URL change
                icsLinkDao.updateLink(updatedLink)
            })
        }
    }

    suspend fun removeLink(link: IcsLinkEntity) {
        withContext(Dispatchers.IO) {
            db.runInTransaction(Runnable {
                icsLinkDao.deleteLink(link)
                courseDao.deleteByIcsName(link.name)
                changeDao.deleteByIcsName(link.name)
            })
        }
    }

    suspend fun markChangesSeen() {
        withContext(Dispatchers.IO) { changeDao.markAllSeen() }
    }

    suspend fun refreshSchedule(): SyncResult = withContext(Dispatchers.IO) {
        var allOk = true
        var anySuccess = false
        val newChanges = mutableListOf<ChangeEntity>()
        try {
            val links = icsLinkDao.getAllLinksSync()
            val nowEpoch = System.currentTimeMillis() / 1000

            for (link in links) {
                val parsedEvents = icsManager.fetchAndParseIcs(link.url)
                if (parsedEvents == null) {
                    // Échec (pas de réseau, serveur en panne...) : on garde les cours déjà enregistrés
                    allOk = false
                    continue
                }
                anySuccess = true

                val allEntities = parsedEvents
                    .map { event ->
                        CourseEntity(
                            title = event.title,
                            description = event.description,
                            location = event.location,
                            startTimeEpoch = event.startEpoch,
                            endTimeEpoch = event.endEpoch,
                            colorArgb = link.colorArgb,
                            icsName = link.name,
                            eventKey = event.eventKey
                        )
                    }
                val futureEntities = allEntities.filter { it.startTimeEpoch >= nowEpoch }

                db.runInTransaction(Runnable {
                    // Première synchro de cet agenda (premier lancement, nouvel agenda) :
                    // on garde TOUS les cours, y compris ceux déjà passés, sans rien supprimer.
                    if (courseDao.countByIcsName(link.name) == 0) {
                        if (allEntities.isNotEmpty()) courseDao.insertCoursesList(allEntities)
                        return@Runnable
                    }
                    val oldFuture = courseDao.getFutureCoursesSync(link.name, nowEpoch)
                    // Pas de détection à la toute première synchro d'un agenda
                    if (oldFuture.isNotEmpty()) {
                        val changes = ChangeDetector.detect(oldFuture, futureEntities, link, nowEpoch)
                        if (changes.isNotEmpty()) {
                            changeDao.insertChanges(changes)
                            newChanges += changes
                        }
                    }
                    courseDao.removeFutureCourses(link.name, nowEpoch)
                    if (futureEntities.isNotEmpty()) courseDao.insertCoursesList(futureEntities)

                    // Historique : on ajoute les cours passés du fichier ICS qui manquent encore
                    // (ex. agenda ajouté avec une ancienne version, ou lien avec ?startDate=...).
                    // Les cours passés déjà enregistrés ne sont jamais supprimés.
                    val pastFromIcs = allEntities.filter { it.startTimeEpoch < nowEpoch }
                    if (pastFromIcs.isNotEmpty()) {
                        val existing = courseDao.getPastCoursesSync(link.name, nowEpoch)
                        val existingKeys = existing.mapTo(HashSet()) { "${it.title}|${it.startTimeEpoch}|${it.endTimeEpoch}" }
                        val existingEventKeys = existing.mapNotNullTo(HashSet()) { it.eventKey.ifBlank { null } }
                        val missing = pastFromIcs.filter {
                            "${it.title}|${it.startTimeEpoch}|${it.endTimeEpoch}" !in existingKeys &&
                                (it.eventKey.isBlank() || it.eventKey !in existingEventKeys)
                        }
                        if (missing.isNotEmpty()) courseDao.insertCoursesList(missing)
                    }
                })
            }

            // On oublie les changements détectés il y a plus de 14 jours
            changeDao.purgeOlderThan(nowEpoch - 14L * 24 * 3600)
        } catch (e: Exception) {
            e.printStackTrace()
            allOk = false
        }
        SyncResult(allOk, anySuccess, newChanges)
    }
}
