package com.example.epivalastor

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ics_links_table")
data class IcsLinkEntity(
    // Identifiant interne : permet de modifier l'URL sans casser la mise à jour
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val name: String, // Nom de l'agenda
    val colorArgb: Int
)
