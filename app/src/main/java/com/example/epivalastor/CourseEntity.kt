package com.example.epivalastor

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses_table")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val location: String,
    val startTimeEpoch: Long,
    val endTimeEpoch: Long,
    val colorArgb: Int,
    val icsName: String, // Le nom de l'agenda rattaché
    val eventKey: String = "" // Identifiant stable de l'événement ICS (sert à détecter les modifications)
)
