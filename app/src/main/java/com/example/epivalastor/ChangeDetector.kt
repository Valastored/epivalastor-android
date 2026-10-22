package com.example.epivalastor

/**
 * Compare les cours futurs avant et après une synchronisation.
 *  1. Un cours identique (titre, horaires, salle) des deux côtés = inchangé, même si son UID a changé.
 *  2. Sinon, même eventKey des deux côtés = cours modifié.
 *  3. Le reste = cours ajouté ou supprimé.
 * Seuls les changements qui touchent les 14 prochains jours sont gardés (évite d'afficher
 * des centaines d'« ajouts » quand un nouveau semestre est publié).
 */
object ChangeDetector {
    private const val HORIZON_SECONDS = 14L * 24 * 3600

    fun detect(
        oldCourses: List<CourseEntity>,
        newCourses: List<CourseEntity>,
        link: IcsLinkEntity,
        nowEpoch: Long
    ): List<ChangeEntity> {
        fun signature(c: CourseEntity) = "${c.title}|${c.startTimeEpoch}|${c.endTimeEpoch}|${c.location}"

        // Étape 1 : on retire les cours identiques
        val oldPool = oldCourses.groupBy(::signature).mapValues { it.value.toMutableList() }
        val unmatchedNew = mutableListOf<CourseEntity>()
        for (n in newCourses) {
            val candidates = oldPool[signature(n)]
            if (!candidates.isNullOrEmpty()) candidates.removeAt(candidates.lastIndex) else unmatchedNew.add(n)
        }
        val unmatchedOld = oldPool.values.flatten().toMutableList()

        // Étape 2 : même identifiant ICS = modification
        val oldByKey = unmatchedOld.filter { it.eventKey.isNotBlank() }
            .groupBy { it.eventKey }
            .mapValues { it.value.toMutableList() }

        val changes = mutableListOf<ChangeEntity>()
        for (n in unmatchedNew) {
            val o = if (n.eventKey.isNotBlank()) oldByKey[n.eventKey]?.removeFirstOrNull() else null
            if (o != null) {
                unmatchedOld.remove(o)
                changes += ChangeEntity(
                    icsName = link.name, colorArgb = link.colorArgb, changeType = ChangeEntity.MODIFIED,
                    title = n.title, oldTitle = if (o.title != n.title) o.title else null,
                    oldStartEpoch = o.startTimeEpoch, oldEndEpoch = o.endTimeEpoch,
                    newStartEpoch = n.startTimeEpoch, newEndEpoch = n.endTimeEpoch,
                    oldLocation = o.location, newLocation = n.location,
                    detectedAtEpoch = nowEpoch
                )
            } else {
                changes += ChangeEntity(
                    icsName = link.name, colorArgb = link.colorArgb, changeType = ChangeEntity.ADDED,
                    title = n.title, newStartEpoch = n.startTimeEpoch, newEndEpoch = n.endTimeEpoch,
                    newLocation = n.location, detectedAtEpoch = nowEpoch
                )
            }
        }

        // Étape 3 : ce qui reste de l'ancien a disparu
        for (o in unmatchedOld) {
            changes += ChangeEntity(
                icsName = link.name, colorArgb = link.colorArgb, changeType = ChangeEntity.REMOVED,
                title = o.title, oldStartEpoch = o.startTimeEpoch, oldEndEpoch = o.endTimeEpoch,
                oldLocation = o.location, detectedAtEpoch = nowEpoch
            )
        }

        val horizon = nowEpoch + HORIZON_SECONDS
        return changes.filter { c ->
            listOfNotNull(c.oldStartEpoch, c.newStartEpoch).any { it in nowEpoch..horizon }
        }
    }
}
