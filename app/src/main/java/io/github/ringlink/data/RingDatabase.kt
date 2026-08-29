package io.github.ringlink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EpochEntity::class, SportEntity::class, DeviceStateEntity::class],
    version = 2,
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
            ).addMigrations(migrateToMultiRing(Settings(context).primaryAddress ?: "unknown"))
                .build()
                .also { instance = it }
        }

        /**
         * Adds ring identity to every table and folds it into the primary key.
         *
         * Existing rows all came from the single ring configured at the time, so they are attributed
         * to it rather than dropped. SQLite cannot alter a primary key in place, hence the rebuild.
         */
        private fun migrateToMultiRing(primary: String) = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE epochs_new (ringId TEXT NOT NULL, counter INTEGER NOT NULL, " +
                        "channel INTEGER NOT NULL, layout TEXT NOT NULL, heartRate INTEGER, " +
                        "hrvRmssd INTEGER, confidence INTEGER NOT NULL, respiratoryRate REAL, " +
                        "spo2 INTEGER, motionSum INTEGER NOT NULL, exportedAt INTEGER, " +
                        "PRIMARY KEY(ringId, counter))",
                )
                db.execSQL(
                    "INSERT INTO epochs_new SELECT ?, counter, channel, layout, heartRate, " +
                        "hrvRmssd, confidence, respiratoryRate, spo2, motionSum, exportedAt FROM epochs",
                    arrayOf(primary),
                )
                db.execSQL("DROP TABLE epochs")
                db.execSQL("ALTER TABLE epochs_new RENAME TO epochs")

                db.execSQL(
                    "CREATE TABLE sport_new (ringId TEXT NOT NULL, counter INTEGER NOT NULL, " +
                        "heartRate INTEGER, steps INTEGER NOT NULL, exportedAt INTEGER, " +
                        "PRIMARY KEY(ringId, counter))",
                )
                db.execSQL(
                    "INSERT INTO sport_new SELECT ?, counter, heartRate, steps, exportedAt FROM sport",
                    arrayOf(primary),
                )
                db.execSQL("DROP TABLE sport")
                db.execSQL("ALTER TABLE sport_new RENAME TO sport")

                db.execSQL(
                    "CREATE TABLE device_state_new (ringId TEXT NOT NULL, recordedAt INTEGER NOT NULL, " +
                        "batteryPercent INTEGER NOT NULL, steps INTEGER NOT NULL, skinTempA REAL NOT NULL, " +
                        "skinTempB REAL NOT NULL, batteryMillivolts INTEGER NOT NULL, " +
                        "onCharger INTEGER NOT NULL, exportedAt INTEGER, PRIMARY KEY(ringId, recordedAt))",
                )
                db.execSQL(
                    "INSERT INTO device_state_new SELECT ?, recordedAt, batteryPercent, steps, " +
                        "skinTempA, skinTempB, batteryMillivolts, onCharger, exportedAt FROM device_state",
                    arrayOf(primary),
                )
                db.execSQL("DROP TABLE device_state")
                db.execSQL("ALTER TABLE device_state_new RENAME TO device_state")
            }
        }
    }
}
