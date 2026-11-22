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

    /** v4: daily climate for the Home view, and imported household power use. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `climate_day` (`place_id` INTEGER NOT NULL, `date` TEXT NOT NULL, `pull_id` INTEGER NOT NULL, `fetched_at` INTEGER NOT NULL, `temperature_mean` REAL, `temperature_max` REAL, `temperature_min` REAL, `relative_humidity_mean` REAL, `dew_point_mean` REAL, `shortwave_radiation_sum` REAL, `sunshine_duration` REAL, `cloud_cover_mean` REAL, PRIMARY KEY(`place_id`, `date`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_climate_day_pull_id` ON `climate_day` (`pull_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `energy_day` (`date` TEXT NOT NULL, `kwh` REAL NOT NULL, `source` TEXT, `imported_at` INTEGER NOT NULL, PRIMARY KEY(`date`))")
        }
    }

    /** v5: actual hours and earlier model runs, for Forecast check. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `observed_hour` (`place_id` INTEGER NOT NULL, `valid_time` INTEGER NOT NULL, `pull_id` INTEGER NOT NULL, `fetched_at` INTEGER NOT NULL, `temperature` REAL, `apparent_temperature` REAL, `precipitation` REAL, `weather_code` INTEGER, `wind_gusts` REAL, `humidity` REAL, `cloud_cover` REAL, PRIMARY KEY(`place_id`, `valid_time`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_observed_hour_pull_id` ON `observed_hour` (`pull_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `previous_run_hour` (`place_id` INTEGER NOT NULL, `valid_time` INTEGER NOT NULL, `lead_days` INTEGER NOT NULL, `pull_id` INTEGER NOT NULL, `temperature` REAL, `precipitation` REAL, PRIMARY KEY(`place_id`, `valid_time`, `lead_days`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_previous_run_hour_pull_id` ON `previous_run_hour` (`pull_id`)")
        }
    }

    /** v6: the user's own "raining" / "dry" reports. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `user_report` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `place_id` INTEGER NOT NULL, `reported_at` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `raining` INTEGER NOT NULL, `forecast_mm` REAL, `forecast_chance` REAL, `radar_here` REAL, `radar_time` INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_report_place_id_reported_at` ON `user_report` (`place_id`, `reported_at`)")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `source_hour` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `place_id` INTEGER NOT NULL, `source` TEXT NOT NULL, `issued_at` INTEGER NOT NULL, `time` INTEGER NOT NULL, `temperature` REAL, `precipitation` REAL, `rain_chance` REAL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_hour_place_id_time` ON `source_hour` (`place_id`, `time`)")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `source_day` (`place_id` INTEGER NOT NULL, `date` TEXT NOT NULL, `source` TEXT NOT NULL, `hours` INTEGER NOT NULL, `temp_abs_sum` REAL NOT NULL, `temp_err_sum` REAL NOT NULL, `temp_n` INTEGER NOT NULL, `rain_right` INTEGER NOT NULL, `rain_n` INTEGER NOT NULL, PRIMARY KEY(`place_id`, `date`, `source`))")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `feeling_report` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `place_id` INTEGER NOT NULL, `reported_at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `temperature` REAL, `feels_like` REAL, `wind_speed` REAL, `wind_gusts` REAL, `wind_direction` REAL, `humidity` REAL, `cloud_cover` REAL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_feeling_report_place_id_reported_at` ON `feeling_report` (`place_id`, `reported_at`)")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
}
