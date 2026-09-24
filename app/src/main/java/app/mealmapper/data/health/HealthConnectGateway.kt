package app.mealmapper.data.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.mealmapper.domain.DayTotals
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

enum class HealthConnectAvailability { AVAILABLE, UPDATE_REQUIRED, NOT_INSTALLED }

/** The only class that talks to Health Connect. Google Health and Samsung Health both read from it. */
class HealthConnectGateway(private val context: Context) {

    // Write: log food. Read: today's total for the ticker (all apps, so it matches Google Health).
    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
    )

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.NOT_INSTALLED
        }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)

    /** Inserts the entry. Writing the same clientId again replaces the earlier record. */
    suspend fun write(entry: NutritionEntry) {
        val zone = ZoneId.systemDefault()
        val start = entry.eatenAt
        // Health Connect needs end > start. One minute is enough for a meal log.
        val end = start.plus(Duration.ofMinutes(1))
        val n = entry.nutrients
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(
                clientRecordId = entry.clientId,
                clientRecordVersion = System.currentTimeMillis(),
            ),
            // Health Connect caps text fields; keep names short and readable in Google Health.
            name = entry.name.take(MAX_NAME_LENGTH),
            mealType = entry.slot.toHealthConnect(),
            energy = Energy.kilocalories(n.energyKcal),
            protein = Mass.grams(n.proteinG),
            totalCarbohydrate = Mass.grams(n.carbsG),
            totalFat = Mass.grams(n.fatG),
            saturatedFat = n.saturatedFatG?.let(Mass::grams),
            sugar = n.sugarG?.let(Mass::grams),
            dietaryFiber = n.fiberG?.let(Mass::grams),
            sodium = n.sodiumMg?.let(Mass::milligrams),
        )
        client.insertRecords(listOf(record))
    }

    /**
     * Totals for the local calendar day. Aggregation lets Health Connect remove duplicates between
     * apps using the user's data-source priority, the same way Google Health does.
     */
    suspend fun todayTotals(): DayTotals {
        val today = LocalDate.now()
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    NutritionRecord.ENERGY_TOTAL,
                    NutritionRecord.PROTEIN_TOTAL,
                    NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL,
                    NutritionRecord.TOTAL_FAT_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(today.atStartOfDay(), today.plusDays(1).atStartOfDay()),
            ),
        )
        return DayTotals(
            energyKcal = result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
            proteinG = result[NutritionRecord.PROTEIN_TOTAL]?.inGrams ?: 0.0,
            carbsG = result[NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL]?.inGrams ?: 0.0,
            fatG = result[NutritionRecord.TOTAL_FAT_TOTAL]?.inGrams ?: 0.0,
        )
    }

    suspend fun delete(clientId: String) {
        client.deleteRecords(
            recordType = NutritionRecord::class,
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(clientId),
        )
    }

    /** Opens the Play Store page for Health Connect (needed on Android 13 and lower). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.android.vending")
            data = Uri.parse(
                "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
            )
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }

    private companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        const val MAX_NAME_LENGTH = 100
    }
}

private fun MealSlot.toHealthConnect(): Int = when (this) {
    MealSlot.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
    MealSlot.LUNCH -> MealType.MEAL_TYPE_LUNCH
    MealSlot.SNACK -> MealType.MEAL_TYPE_SNACK
    MealSlot.DINNER -> MealType.MEAL_TYPE_DINNER
}
