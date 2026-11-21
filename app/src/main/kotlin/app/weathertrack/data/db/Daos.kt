package app.weathertrack.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPullDao {
    @Insert
    suspend fun insert(pull: RawPullEntity): Long

    @Query(
        "SELECT * FROM raw_pull WHERE place_id = :placeId AND source = :source AND endpoint = :endpoint " +
            "AND http_status = 200 ORDER BY fetched_at DESC LIMIT 1"
    )
    suspend fun latest(placeId: Long, source: String, endpoint: String): RawPullEntity?

    @Query("SELECT COUNT(*) FROM raw_pull")
    fun count(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM raw_pull")
    fun totalBodyBytes(): Flow<Long>

    @Query("SELECT MIN(fetched_at) FROM raw_pull")
    fun firstFetchedAt(): Flow<Long?>
}

@Dao
interface ForecastDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHours(rows: List<ForecastHourEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(rows: List<ForecastDayEntity>)

    @Query("SELECT COUNT(*) FROM forecast_hour")
    fun hourCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM forecast_day")
    fun dayCount(): Flow<Int>
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM place ORDER BY is_device_location DESC, sort_order, id")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM place ORDER BY is_device_location DESC, sort_order, id")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM place WHERE id = :id")
    suspend fun byId(id: Long): PlaceEntity?

    @Query("SELECT * FROM place WHERE id = :id")
    fun observeById(id: Long): Flow<PlaceEntity?>

    @Query("SELECT * FROM place WHERE is_device_location = 1 LIMIT 1")
    suspend fun deviceLocation(): PlaceEntity?

    @Query("SELECT COUNT(*) FROM place")
    suspend fun count(): Int

    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Delete
    suspend fun delete(place: PlaceEntity)
}

data class ObservedRange(
    @androidx.room.ColumnInfo(name = "day_count") val days: Int,
    @androidx.room.ColumnInfo(name = "first_date") val first: String?,
    @androidx.room.ColumnInfo(name = "last_date") val last: String?,
)

@Dao
interface ObservedDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ObservedDayEntity>)

    @Query("SELECT MAX(date) FROM observed_day WHERE place_id = :placeId")
    suspend fun lastDate(placeId: Long): String?

    /** All years of one calendar month ("01".."12") for a place. */
    @Query("SELECT * FROM observed_day WHERE place_id = :placeId AND substr(date, 6, 2) = :month ORDER BY date")
    suspend fun forMonth(placeId: Long, month: String): List<ObservedDayEntity>

    @Query("SELECT COUNT(*) AS day_count, MIN(`date`) AS first_date, MAX(`date`) AS last_date FROM observed_day WHERE place_id = :placeId")
    fun observeRange(placeId: Long): Flow<ObservedRange>

    @Query("SELECT COUNT(*) AS day_count, MIN(`date`) AS first_date, MAX(`date`) AS last_date FROM observed_day WHERE place_id = :placeId")
    suspend fun range(placeId: Long): ObservedRange?

    @Query("SELECT COUNT(*) FROM observed_day")
    fun count(): Flow<Int>
}
