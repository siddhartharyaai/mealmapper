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
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import java.time.Duration
import java.time.ZoneId

enum class HealthConnectAvailability { AVAILABLE, UPDATE_REQUIRED, NOT_INSTALLED }

/** The only class that talks to Health Connect. Google Health and Samsung Health both read from it. */
class HealthConnectGateway(private val context: Context) {

    val requiredPermissions: Set<String> =
        setOf(HealthPermission.getWritePermission(NutritionRecord::class))

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
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(
                clientRecordId = entry.clientId,
                clientRecordVersion = System.currentTimeMillis(),
            ),
            name = entry.name,
            mealType = entry.slot.toHealthConnect(),
            energy = Energy.kilocalories(entry.energyKcal),
            protein = Mass.grams(entry.proteinG),
            totalCarbohydrate = Mass.grams(entry.carbsG),
            totalFat = Mass.grams(entry.fatG),
            saturatedFat = entry.saturatedFatG?.let(Mass::grams),
            sugar = entry.sugarG?.let(Mass::grams),
            dietaryFiber = entry.fiberG?.let(Mass::grams),
            sodium = entry.sodiumMg?.let(Mass::milligrams),
        )
        client.insertRecords(listOf(record))
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
    }
}

private fun MealSlot.toHealthConnect(): Int = when (this) {
    MealSlot.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
    MealSlot.LUNCH -> MealType.MEAL_TYPE_LUNCH
    MealSlot.SNACK -> MealType.MEAL_TYPE_SNACK
    MealSlot.DINNER -> MealType.MEAL_TYPE_DINNER
}
