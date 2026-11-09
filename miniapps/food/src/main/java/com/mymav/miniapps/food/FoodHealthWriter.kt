package com.mymav.miniapps.food

import android.util.Log

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Writes a scanned meal to Health Connect, where Samsung Health picks it up through its own
 * Health Connect sync. One record per scan: repeated saves (e.g. after changing the portion)
 * upsert the same clientRecordId with a higher version, so the diary never gets duplicates.
 */
object FoodHealthWriter {
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
    )

    sealed class Outcome {
        /** Read back from Health Connect after the write. */
        data class Confirmed(val recordId: String, val at: Instant) : Outcome()
        data class NeedsPermission(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun available(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context.applicationContext) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!available(context)) return@withContext false
        runCatching {
            HealthConnectClient.getOrCreate(context.applicationContext)
                .permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
        }.getOrDefault(false)
    }

    fun clientRecordId(scanId: String) = "mymav-food-$scanId"

    suspend fun save(
        context: Context,
        scanId: String,
        version: Long,
        eatenAt: Instant,
        result: FoodScanResult,
        portionFactor: Double,
    ): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!available(app)) return@withContext Outcome.Failed("Health Connect isn't available on this phone.")
        val client = HealthConnectClient.getOrCreate(app)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (!granted.containsAll(PERMISSIONS)) {
            return@withContext Outcome.NeedsPermission("Allow Health Connect on the phone: Food, Connect Samsung Health.")
        }
        val zone = ZoneId.systemDefault()
        val start = eatenAt.minusSeconds(60)
        val offset = zone.rules.getOffset(eatenAt)
        fun scaled(v: Int?) = v?.let { it * portionFactor }
        val id = clientRecordId(scanId)
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = eatenAt,
            endZoneOffset = offset,
            name = result.label.take(100),
            energy = scaled(result.kcal)?.let { Energy.kilocalories(it) },
            protein = scaled(result.proteinG)?.let { Mass.grams(it) },
            totalCarbohydrate = scaled(result.carbsG)?.let { Mass.grams(it) },
            totalFat = scaled(result.fatG)?.let { Mass.grams(it) },
            mealType = mealTypeAt(ZonedDateTime.ofInstant(eatenAt, zone).hour),
            metadata = Metadata(
                clientRecordId = id,
                clientRecordVersion = version,
                recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY,
            ),
        )
        runCatching { client.insertRecords(listOf(record)) }.onFailure {
            return@withContext Outcome.Failed("Health Connect refused the meal: ${it.message ?: it.javaClass.simpleName}")
        }
        val mine = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    NutritionRecord::class,
                    TimeRangeFilter.between(start.minusSeconds(60), eatenAt.plusSeconds(60)),
                ),
            ).records.filter { it.metadata.clientRecordId == id }
        }.getOrDefault(emptyList())
        val back = mine.firstOrNull { it.metadata.clientRecordVersion == version }
        Log.i(
            "FoodHealth",
            "upsert $id v$version kcal=${back?.energy?.inKilocalories?.toInt()} " +
                "copies=${mine.size} confirmed=${back != null} hcId=${back?.metadata?.id}",
        )
        if (back == null) {
            Outcome.Failed("Saved, but Health Connect didn't return it. Check Samsung Health before saving again.")
        } else {
            Outcome.Confirmed(back.metadata.id, Instant.now())
        }
    }

    private fun mealTypeAt(hour: Int): Int = when (hour) {
        in 5..10 -> MealType.MEAL_TYPE_BREAKFAST
        in 11..14 -> MealType.MEAL_TYPE_LUNCH
        in 17..21 -> MealType.MEAL_TYPE_DINNER
        else -> MealType.MEAL_TYPE_SNACK
    }
}
