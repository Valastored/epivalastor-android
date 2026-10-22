package com.example.epivalastor

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Un changement détecté dans l'emploi du temps lors d'une synchronisation. */
@Entity(tableName = "changes_table")
data class ChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val icsName: String,
    val colorArgb: Int,
    val changeType: String, // ADDED, REMOVED, MODIFIED
    val title: String,
    val oldTitle: String? = null,
    val oldStartEpoch: Long? = null,
    val oldEndEpoch: Long? = null,
    val newStartEpoch: Long? = null,
    val newEndEpoch: Long? = null,
    val oldLocation: String? = null,
    val newLocation: String? = null,
    val detectedAtEpoch: Long,
    val seen: Boolean = false
) {
    companion object {
        const val ADDED = "ADDED"
        const val REMOVED = "REMOVED"
        const val MODIFIED = "MODIFIED"
    }
}
