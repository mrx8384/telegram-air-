package com.tcam.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One finished (or stopped) batch, with its final report. */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val action: String,
    val engine: String?,
    val dryRun: Boolean,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val outcome: String,
    val report: String,
)

/** One log line. Kept so problems can be reviewed after the fact. */
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val level: String,
    val message: String,
)

@Dao
interface RunDao {
    @Insert suspend fun insert(run: RunEntity): Long
    @Query("SELECT * FROM runs ORDER BY startedAt DESC") fun observeAll(): Flow<List<RunEntity>>
    @Query("DELETE FROM runs") suspend fun clear()
}

@Dao
interface LogDao {
    @Insert suspend fun insert(log: LogEntity)
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit") suspend fun latest(limit: Int): List<LogEntity>
    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT :keep)") suspend fun trim(keep: Int)
    @Query("DELETE FROM logs") suspend fun clear()
}

@Database(entities = [RunEntity::class, LogEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runs(): RunDao
    abstract fun logs(): LogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "channel_manager.db").build()
    }
}
