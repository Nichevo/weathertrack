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

    @Query("SELECT * FROM raw_pull WHERE url = :url AND http_status = 200 ORDER BY fetched_at DESC LIMIT 1")
    suspend fun latestForUrl(url: String): RawPullEntity?

    @Query("SELECT COUNT(*) FROM raw_pull")
    fun count(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM raw_pull")
    fun totalBodyBytes(): Flow<Long>

    @Query("SELECT MIN(fetched_at) FROM raw_pull")
    fun firstFetchedAt(): Flow<Long?>

    @Query("SELECT COALESCE(SUM(LENGTH(body_gz)), 0) FROM raw_pull")
    fun compressedBytes(): Flow<Long>

    @Query("SELECT id, place_id AS placeId, fetched_at AS fetchedAt FROM raw_pull WHERE source = :source AND endpoint = :endpoint AND fetched_at < :before")
    suspend fun pullsBefore(source: String, endpoint: String, before: Long): List<PullRow>

    @Query("SELECT id, body FROM raw_pull WHERE fetched_at < :before AND body != '' AND body_gz IS NULL LIMIT :limit")
    suspend fun uncompressed(before: Long, limit: Int): List<IdBody>

    @Query("UPDATE raw_pull SET body = '', body_gz = :gz WHERE id = :id")
    suspend fun setCompressed(id: Long, gz: ByteArray)

    @Query("DELETE FROM raw_pull WHERE http_status != 200 AND fetched_at < :before")
    suspend fun deleteFailedBefore(before: Long): Int

    @Query("DELETE FROM raw_pull WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>): Int

    @Query("SELECT endpoint, http_status AS status, fetched_at AS fetchedAt FROM raw_pull WHERE http_status != 200 ORDER BY fetched_at DESC LIMIT :limit")
    suspend fun recentFailures(limit: Int): List<FailedPull>

    @Query("SELECT MAX(fetched_at) FROM raw_pull WHERE endpoint = :endpoint AND http_status = 200")
    suspend fun lastSuccess(endpoint: String): Long?

    @Query(
        "SELECT endpoint, COUNT(*) AS pulls, COALESCE(SUM(LENGTH(body)), 0) + COALESCE(SUM(LENGTH(body_gz)), 0) AS bytes " +
            "FROM raw_pull WHERE fetched_at >= :since GROUP BY endpoint ORDER BY pulls DESC"
    )
    suspend fun downloadsSince(since: Long): List<EndpointUse>
}

data class EndpointUse(val endpoint: String, val pulls: Int, val bytes: Long)

data class PullRow(val id: Long, val placeId: Long?, val fetchedAt: Long)
data class IdBody(val id: Long, val body: String)
data class FailedPull(val endpoint: String, val status: Int, val fetchedAt: Long)

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

    @Query("DELETE FROM forecast_hour WHERE pull_id IN (:pullIds)")
    suspend fun deleteHoursOf(pullIds: List<Long>): Int

    @Query("DELETE FROM forecast_day WHERE pull_id IN (:pullIds)")
    suspend fun deleteDaysOf(pullIds: List<Long>): Int
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

    @Query("SELECT * FROM observed_day WHERE place_id = :placeId ORDER BY date")
    suspend fun all(placeId: Long): List<ObservedDayEntity>

    @Query("SELECT MAX(date) FROM observed_day WHERE place_id = :placeId")
    suspend fun lastDate(placeId: Long): String?

    @Query("SELECT date FROM observed_day WHERE place_id = :placeId")
    suspend fun dates(placeId: Long): List<String>

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

@Dao
interface ClimateDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ClimateDayEntity>)

    @Query("SELECT MAX(date) FROM climate_day WHERE place_id = :placeId")
    suspend fun lastDate(placeId: Long): String?

    @Query("SELECT date FROM climate_day WHERE place_id = :placeId")
    suspend fun dates(placeId: Long): List<String>

    @Query("SELECT * FROM climate_day WHERE place_id = :placeId ORDER BY date")
    suspend fun all(placeId: Long): List<ClimateDayEntity>

    @Query("SELECT COUNT(*) AS day_count, MIN(`date`) AS first_date, MAX(`date`) AS last_date FROM climate_day WHERE place_id = :placeId")
    fun observeRange(placeId: Long): Flow<ObservedRange>
}

@Dao
interface EnergyDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<EnergyDayEntity>)

    @Query("SELECT * FROM energy_day ORDER BY date")
    suspend fun all(): List<EnergyDayEntity>

    @Query("SELECT COUNT(*) AS day_count, MIN(`date`) AS first_date, MAX(`date`) AS last_date FROM energy_day")
    fun observeRange(): Flow<ObservedRange>

    @Query("DELETE FROM energy_day")
    suspend fun clear()
}

@Dao
interface ForecastCheckDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObserved(rows: List<ObservedHourEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuns(rows: List<PreviousRunHourEntity>)

    @Query("SELECT * FROM observed_hour WHERE place_id = :placeId AND valid_time >= :from ORDER BY valid_time")
    suspend fun observed(placeId: Long, from: Long): List<ObservedHourEntity>

    @Query("SELECT * FROM previous_run_hour WHERE place_id = :placeId AND valid_time >= :from")
    suspend fun runs(placeId: Long, from: Long): List<PreviousRunHourEntity>

    @Query("SELECT COUNT(*) FROM observed_hour WHERE place_id = :placeId")
    suspend fun observedCount(placeId: Long): Int

    @Query("SELECT COUNT(*) FROM previous_run_hour WHERE place_id = :placeId")
    suspend fun runCount(placeId: Long): Int

    /** Forecast hours this app stored, 0 to 24 hours ahead of when they were issued. */
    @Query(
        "SELECT * FROM forecast_hour WHERE place_id = :placeId AND valid_time >= :from " +
            "AND valid_time < :until AND lead_hours BETWEEN 0 AND 24"
    )
    suspend fun issuedNearTerm(placeId: Long, from: Long, until: Long): List<ForecastHourEntity>

    @Query("SELECT COUNT(DISTINCT pull_id) FROM forecast_hour WHERE place_id = :placeId")
    suspend fun storedPulls(placeId: Long): Int
}

@Dao
interface UserReportDao {
    @Insert
    suspend fun insert(report: UserReportEntity): Long

    @Query("SELECT * FROM user_report WHERE place_id = :placeId AND reported_at >= :from ORDER BY reported_at")
    suspend fun since(placeId: Long, from: Long): List<UserReportEntity>

    @Query("SELECT COUNT(*) FROM user_report")
    fun count(): Flow<Int>
}

@Dao
interface FeelingReportDao {
    @Insert
    suspend fun insert(row: FeelingReportEntity): Long

    @Query("SELECT * FROM feeling_report WHERE place_id = :placeId AND reported_at >= :from ORDER BY reported_at")
    suspend fun since(placeId: Long, from: Long): List<FeelingReportEntity>
}

@Dao
interface SourceDayDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SourceDayEntity>)

    @Query("SELECT * FROM source_day WHERE place_id = :placeId AND date >= :from ORDER BY date DESC")
    suspend fun since(placeId: Long, from: String): List<SourceDayEntity>
}

@Dao
interface SourceHourDao {
    @Insert
    suspend fun insertAll(rows: List<SourceHourEntity>)

    @Query("SELECT * FROM source_hour WHERE place_id = :placeId AND time >= :from AND time <= :to ORDER BY time")
    suspend fun between(placeId: Long, from: Long, to: Long): List<SourceHourEntity>

    @Query("SELECT MAX(issued_at) FROM source_hour WHERE place_id = :placeId AND source = :source")
    suspend fun lastIssued(placeId: Long, source: String): Long?

    /** Only rows issued [minLeadS]..[maxLeadS] ahead of their hour: the ones scoring looks at. */
    @Query("SELECT * FROM source_hour WHERE place_id = :placeId AND time >= :from AND time <= :to AND time - issued_at >= :minLeadS AND time - issued_at <= :maxLeadS")
    suspend fun forScoring(placeId: Long, from: Long, to: Long, minLeadS: Long, maxLeadS: Long): List<SourceHourEntity>

    /**
     * Keeps the table small: drops rows of deleted places, rows older than [before], and past hours
     * that a later forecast (still at least [minLeadS] ahead) has replaced, since scoring only uses that later one.
     */
    @Query("""DELETE FROM source_hour WHERE place_id NOT IN (SELECT id FROM place) OR time < :before OR (time < :now AND EXISTS (
        SELECT 1 FROM source_hour b WHERE b.place_id = source_hour.place_id AND b.source = source_hour.source
        AND b.time = source_hour.time AND b.issued_at > source_hour.issued_at AND b.time - b.issued_at >= :minLeadS))""")
    suspend fun prune(now: Long, before: Long, minLeadS: Long): Int

    @Query("SELECT COUNT(*) FROM source_hour")
    fun count(): Flow<Int>
}
