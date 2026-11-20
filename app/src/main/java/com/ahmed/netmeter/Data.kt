package com.ahmed.netmeter

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Process
import android.provider.Settings
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow



/** One app's usage inside a time window. */
data class AppUsage(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val bytes: Long,
)

/** Reads network usage from Android's NetworkStatsManager. */
object UsageStatsHelper {

    const val NETWORK_MOBILE = ConnectivityManager.TYPE_MOBILE
    const val NETWORK_WIFI = ConnectivityManager.TYPE_WIFI

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun dayRange(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun hourRange(date: LocalDate, hour: Int): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return start to (start + 3_600_000L)
    }

    private fun networkStatsManager(context: Context): NetworkStatsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

    /** Per-app totals between two timestamps. */
    fun queryAppUsage(
        context: Context,
        networkType: Int,
        startMillis: Long,
        endMillis: Long,
    ): List<AppUsage> {
        if (!hasUsageAccess(context)) return emptyList()
        val pm = context.packageManager
        return try {
            // queryDetails is the public detailed query; buckets carry the uid,
            // so grouping by uid gives per-app totals for the window.
            val stats = networkStatsManager(context)
                .queryDetails(networkType, null, startMillis, endMillis)
            val bucket = NetworkStats.Bucket()
            val perUid = HashMap<Int, Long>()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val total = bucket.rxBytes + bucket.txBytes
                if (total > 0L) perUid[bucket.uid] = (perUid[bucket.uid] ?: 0L) + total
            }
            stats.close()
            perUid.mapNotNull { (uid, bytes) -> toAppUsage(pm, uid, bytes) }
                .sortedByDescending { it.bytes }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Whole-device total per calendar day between two timestamps. */
    fun queryDailyTotals(
        context: Context,
        networkType: Int,
        startMillis: Long,
        endMillis: Long,
    ): Map<LocalDate, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        return try {
            val stats = networkStatsManager(context)
                .queryDetails(networkType, null, startMillis, endMillis)
            val bucket = NetworkStats.Bucket()
            val result = HashMap<LocalDate, Long>()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val date = Instant.ofEpochMilli(bucket.startTimeStamp)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                result[date] = (result[date] ?: 0L) + bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Per-app usage inside one hour of one day. */
    fun queryHourlyAppUsage(
        context: Context,
        networkType: Int,
        hourStart: Long,
        hourEnd: Long,
        dayStart: Long,
        dayEnd: Long,
    ): List<AppUsage> {
        if (!hasUsageAccess(context)) return emptyList()
        val pm = context.packageManager
        val nsm = networkStatsManager(context)
        val uids = queryAppUsage(context, networkType, dayStart, dayEnd).map { it.uid }
        val perUid = HashMap<Int, Long>()
        for (uid in uids) {
            try {
                val stats = nsm.queryDetailsForUid(networkType, null, hourStart, hourEnd, uid)
                val bucket = NetworkStats.Bucket()
                var total = 0L
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    total += bucket.rxBytes + bucket.txBytes
                }
                stats.close()
                if (total > 0L) perUid[uid] = total
            } catch (e: Exception) {
                // Some uids cannot be queried in detail; skip them.
            }
        }
        return perUid.mapNotNull { (uid, bytes) -> toAppUsage(pm, uid, bytes) }
            .sortedByDescending { it.bytes }
    }

    private fun toAppUsage(pm: PackageManager, uid: Int, bytes: Long): AppUsage? {
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return null
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            AppUsage(uid, pkg, info.loadLabel(pm).toString(), safeIcon(pm, pkg), bytes)
        } catch (e: PackageManager.NameNotFoundException) {
            AppUsage(uid, pkg, pkg, null, bytes)
        }
    }

    private fun safeIcon(pm: PackageManager, pkg: String): Drawable? =
        try {
            pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
}



/** Small settings store (retention period + chosen network). */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences("netmeter_prefs", Context.MODE_PRIVATE)

    var retentionMonths: Int
        get() = prefs.getInt(KEY_RETENTION, 3)
        set(value) {
            prefs.edit().putInt(KEY_RETENTION, value).apply()
        }

    var networkType: Int
        get() = prefs.getInt(KEY_NETWORK, UsageStatsHelper.NETWORK_MOBILE)
        set(value) {
            prefs.edit().putInt(KEY_NETWORK, value).apply()
        }

    companion object {
        private const val KEY_RETENTION = "retention_months"
        private const val KEY_NETWORK = "network_type"
    }
}



/** One recorded day of usage. The app keeps these for the chosen retention period. */
@Entity(tableName = "daily_snapshots", primaryKeys = ["date", "networkType"])
data class DailySnapshot(
    val date: String, // yyyy-MM-dd
    val networkType: Int,
    val totalBytes: Long,
    val updatedAt: Long,
)

@Dao
interface SnapshotDao {

    @Upsert
    suspend fun upsert(snapshot: DailySnapshot)

    @Query("SELECT * FROM daily_snapshots WHERE date >= :since ORDER BY date ASC")
    fun snapshotsSince(since: String): Flow<List<DailySnapshot>>

    @Query("DELETE FROM daily_snapshots WHERE date < :cutoff")
    suspend fun pruneBefore(cutoff: String)
}

@Database(entities = [DailySnapshot::class], version = 1, exportSchema = false)
abstract class UsageDatabase : RoomDatabase() {

    abstract fun snapshotDao(): SnapshotDao

    companion object {
        @Volatile
        private var instance: UsageDatabase? = null

        fun get(context: Context): UsageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UsageDatabase::class.java,
                    "netmeter.db"
                ).build().also { instance = it }
            }
    }
}



/** Records today's total once a day so history survives for the retention period. */
class SnapshotWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            if (!UsageStatsHelper.hasUsageAccess(context)) return Result.success()

            val prefs = Prefs(context)
            val today = LocalDate.now()
            val (start, end) = UsageStatsHelper.dayRange(today)
            val totals = UsageStatsHelper.queryDailyTotals(context, prefs.networkType, start, end)
            val db = UsageDatabase.get(context)
            db.snapshotDao().upsert(
                DailySnapshot(
                    date = today.toString(),
                    networkType = prefs.networkType,
                    totalBytes = totals[today] ?: 0L,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            db.snapshotDao().pruneBefore(today.minusMonths(prefs.retentionMonths.toLong()).toString())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "daily_snapshot"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SnapshotWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
