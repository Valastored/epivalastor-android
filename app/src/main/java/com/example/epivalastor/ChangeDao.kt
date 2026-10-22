package com.example.epivalastor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChangeDao {
    @Query("SELECT * FROM changes_table ORDER BY detectedAtEpoch DESC, id DESC")
    fun getAllChangesFlow(): Flow<List<ChangeEntity>>

    @Query("SELECT COUNT(*) FROM changes_table WHERE seen = 0")
    fun getUnseenCountFlow(): Flow<Int>

    @Insert
    fun insertChanges(changes: List<ChangeEntity>)

    @Query("UPDATE changes_table SET seen = 1 WHERE seen = 0")
    fun markAllSeen()

    @Query("DELETE FROM changes_table WHERE icsName = :icsName")
    fun deleteByIcsName(icsName: String)

    @Query("UPDATE changes_table SET icsName = :newName, colorArgb = :newColor WHERE icsName = :oldName")
    fun updateInfo(oldName: String, newName: String, newColor: Int)

    @Query("DELETE FROM changes_table WHERE detectedAtEpoch < :beforeEpoch")
    fun purgeOlderThan(beforeEpoch: Long)
}
