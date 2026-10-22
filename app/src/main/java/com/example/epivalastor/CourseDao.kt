package com.example.epivalastor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    // Les fonctions qui retournent un Flow n'ont jamais besoin de suspend
    @Query("SELECT * FROM courses_table ORDER BY startTimeEpoch ASC")
    fun getAllCourses(): Flow<List<CourseEntity>>

    // Cours futurs d'un agenda (pour comparer avant/après une synchro)
    @Query("SELECT * FROM courses_table WHERE icsName = :icsName AND startTimeEpoch >= :fromEpoch")
    fun getFutureCoursesSync(icsName: String, fromEpoch: Long): List<CourseEntity>

    // Cours pas encore terminés (utilisé par les notifications en arrière-plan)
    @Query("SELECT * FROM courses_table WHERE endTimeEpoch > :fromEpoch ORDER BY startTimeEpoch ASC")
    fun getCoursesEndingAfterSync(fromEpoch: Long): List<CourseEntity>

    // Cours déjà passés d'un agenda (pour compléter l'historique sans doublons)
    @Query("SELECT * FROM courses_table WHERE icsName = :icsName AND startTimeEpoch < :beforeEpoch")
    fun getPastCoursesSync(icsName: String, beforeEpoch: Long): List<CourseEntity>

    @Query("SELECT COUNT(*) FROM courses_table WHERE icsName = :icsName")
    fun countByIcsName(icsName: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCoursesList(courses: List<CourseEntity>)

    @Query("DELETE FROM courses_table WHERE icsName = :icsName AND startTimeEpoch >= :currentEpoch")
    fun removeFutureCourses(icsName: String, currentEpoch: Long)

    @Query("DELETE FROM courses_table WHERE icsName = :icsName")
    fun deleteByIcsName(icsName: String)

    @Query("UPDATE courses_table SET icsName = :newName, colorArgb = :newColor WHERE icsName = :oldName")
    fun updateCourseInfo(oldName: String, newName: String, newColor: Int)

    @Query("DELETE FROM courses_table")
    fun wipeAllCourses()
}
