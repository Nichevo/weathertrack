package app.weathertrack.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations. Stored data must never be lost on upgrade, so there is no destructive
 * fallback. CI checks that each CREATE statement here matches what Room generates
 * (.github/scripts/check_migrations.py).
 */
object Migrations {
    /** v2: observed daily history from the Open-Meteo archive. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `observed_day` (`place_id` INTEGER NOT NULL, `date` TEXT NOT NULL, `pull_id` INTEGER NOT NULL, `fetched_at` INTEGER NOT NULL, `temperature_max` REAL, `temperature_min` REAL, `temperature_mean` REAL, `apparent_temperature_max` REAL, `apparent_temperature_min` REAL, `precipitation_sum` REAL, `rain_sum` REAL, `precipitation_hours` REAL, `wind_gusts_max` REAL, `sunshine_duration` REAL, `weather_code` INTEGER, PRIMARY KEY(`place_id`, `date`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_observed_day_pull_id` ON `observed_day` (`pull_id`)")
        }
    }

    /** v3: low, mid and high cloud cover on hourly forecasts. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `forecast_hour` ADD COLUMN `cloud_cover_low` REAL")
            db.execSQL("ALTER TABLE `forecast_hour` ADD COLUMN `cloud_cover_mid` REAL")
            db.execSQL("ALTER TABLE `forecast_hour` ADD COLUMN `cloud_cover_high` REAL")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
