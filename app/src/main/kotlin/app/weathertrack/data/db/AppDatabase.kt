package app.weathertrack.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RawPullEntity::class, ForecastHourEntity::class, ForecastDayEntity::class, PlaceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawPullDao(): RawPullDao
    abstract fun forecastDao(): ForecastDao
    abstract fun placeDao(): PlaceDao

    companion object {
        const val NAME = "weathertrack.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
