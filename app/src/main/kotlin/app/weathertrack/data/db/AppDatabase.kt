package app.weathertrack.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RawPullEntity::class, ForecastHourEntity::class, ForecastDayEntity::class, PlaceEntity::class,
        ObservedDayEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawPullDao(): RawPullDao
    abstract fun forecastDao(): ForecastDao
    abstract fun placeDao(): PlaceDao
    abstract fun observedDayDao(): ObservedDayDao

    companion object {
        const val NAME = "weathertrack.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
