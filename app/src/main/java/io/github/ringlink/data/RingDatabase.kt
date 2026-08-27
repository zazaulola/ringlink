package io.github.ringlink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EpochEntity::class, SportEntity::class, DeviceStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RingDatabase : RoomDatabase() {
    abstract fun dao(): RingDao

    companion object {
        @Volatile private var instance: RingDatabase? = null

        fun get(context: Context): RingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RingDatabase::class.java,
                "ringlink.db",
            ).build().also { instance = it }
        }
    }
}
