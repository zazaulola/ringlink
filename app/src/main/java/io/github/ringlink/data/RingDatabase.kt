package io.github.ringlink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EpochEntity::class,
        SportEntity::class,
        DeviceStateEntity::class,
        StepQuarterEntity::class,
        WeatherHourEntity::class,
    ],
    version = 6,
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
            ).addMigrations(
                migrateToMultiRing(Settings(context).primaryAddress ?: "unknown"),
                MIGRATION_2_3,
                MIGRATION_3_4,
                migrateToStepQuarters(Settings(context)),
                migrateToWear(Settings(context)),
            )
                .build()
                .also { instance = it }
        }

        /**
         * Keeps the ring's raw state byte, so its meaning can be learned from real data.
         *
         * The entity deliberately does NOT declare `@ColumnInfo(defaultValue = "0")` to match the
         * SQL default here. Adding it changes the entity's identity hash while the version stays
         * at 3, so every database already migrated to 3 fails to open with "Room cannot verify the
         * data integrity". Room accepts the difference as it stands; leave it alone.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_state ADD COLUMN state INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Keeps what a docked ring reports about its charging case.
         *
         * Both columns are nullable with no SQL default, matching the entity exactly: last time a
         * column was added with a default the entity did not declare, the mismatch only became a
         * crash once someone "fixed" it. Nullable, defaultless columns have nothing to disagree on.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_state ADD COLUMN caseBattery INTEGER")
                db.execSQL("ALTER TABLE device_state ADD COLUMN caseCharging INTEGER")
            }
        }

        /**
         * Adds the derived per-quarter steps. Created empty; it is filled from device_state the
         * first time steps are read. The SQL is Room's own for the entity, so the schema check
         * passes.
         *
         * Upgrading is also what marks Health Connect's steps for rewriting: this version dates
         * them by the phone's clock, and what earlier versions wrote sits under other ids.
         */
        private fun migrateToStepQuarters(settings: Settings) = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `step_quarters` (`ringId` TEXT NOT NULL, " +
                        "`startMillis` INTEGER NOT NULL, `steps` INTEGER NOT NULL, `worn` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ringId`, `startMillis`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_step_quarters_startMillis` ON `step_quarters` (`startMillis`)",
                )
                settings.stepsPhoneClockPending = true
            }
        }

        /**
         * Keeps the link's signal strength with each reading, and the weather a weather app sends.
         * The column is nullable with no default, matching the entity, for the reason given at
         * MIGRATION_3_4. Whether a quarter was worn is judged differently from this version on,
         * so the stored quarters are rebuilt.
         */
        private fun migrateToWear(settings: Settings) = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE device_state ADD COLUMN rssi INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weather_hours` (`hourStart` INTEGER NOT NULL, " +
                        "`tempC` REAL NOT NULL, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`hourStart`))",
                )
                settings.invalidateStepQuarters()
            }
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
