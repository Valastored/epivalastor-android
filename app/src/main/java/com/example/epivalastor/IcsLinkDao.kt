package com.example.epivalastor

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IcsLinkDao {
    @Query("SELECT * FROM ics_links_table")
    fun getAllLinksFlow(): Flow<List<IcsLinkEntity>>

    @Query("SELECT * FROM ics_links_table")
    fun getAllLinksSync(): List<IcsLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLink(link: IcsLinkEntity)

    @Update
    fun updateLink(link: IcsLinkEntity)

    @Delete
    fun deleteLink(link: IcsLinkEntity)
}