package com.example.epivalastor

/**
 * Un événement lu depuis un fichier ICS.
 * Les heures sont stockées en secondes epoch (instant absolu), donc indépendantes
 * du fuseau horaire réglé sur le téléphone.
 */
data class CourseEvent(
    val eventKey: String, // Identifiant stable (UID ICS + date d'origine pour les récurrences)
    val title: String,
    val description: String,
    val location: String,
    val startEpoch: Long,
    val endEpoch: Long
)
