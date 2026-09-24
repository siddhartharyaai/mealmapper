package app.mealmapper.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.mealSlotFor
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val availability: HealthConnectAvailability? = null,
    val permissionGranted: Boolean = false,
    val permissionDenied: Boolean = false,
    val lastWrittenAt: Instant? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Setup check: prove that one entry written here shows up in Google Health and Samsung Health
 * before any scanning feature is built on top.
 */
class SetupViewModel(private val healthConnect: HealthConnectGateway) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    val requiredPermissions: Set<String> get() = healthConnect.requiredPermissions

    fun refresh() {
        val availability = healthConnect.availability()
        _state.update { it.copy(availability = availability) }
        if (availability != HealthConnectAvailability.AVAILABLE) return
        viewModelScope.launch {
            val granted = runCatching { healthConnect.hasAllPermissions() }.getOrDefault(false)
            _state.update { it.copy(permissionGranted = granted, permissionDenied = it.permissionDenied && !granted) }
        }
    }

    fun onPermissionResult(granted: Set<String>) {
        val ok = granted.containsAll(healthConnect.requiredPermissions)
        _state.update { it.copy(permissionGranted = ok, permissionDenied = !ok) }
    }

    fun writeTestEntry() = runAction(success = "Test entry written. Check both apps now.") {
        val now = Instant.now()
        healthConnect.write(TEST_ENTRY.copy(eatenAt = now, slot = mealSlotFor(LocalTime.now())))
        _state.update { it.copy(lastWrittenAt = now) }
    }

    fun removeTestEntry() = runAction(success = "Test entry removed from Health Connect.") {
        healthConnect.delete(TEST_ENTRY.clientId)
        _state.update { it.copy(lastWrittenAt = null) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun runAction(success: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = runCatching { block() }.fold(
                onSuccess = { success },
                onFailure = { "Health Connect did not accept the request: ${it.message ?: it.javaClass.simpleName}" },
            )
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    companion object {
        /**
         * 1 cup (150 ml) masala chai: 75 ml toned milk, 2 tsp sugar. Values from IFCT 2017 toned milk
         * plus 8 g sucrose. Real enough to recognise in both apps, small enough to not distort the day.
         */
        val TEST_ENTRY = NutritionEntry(
            clientId = "mealmapper-setup-test",
            name = "Masala chai, 1 cup (Meal Mapper test)",
            slot = MealSlot.SNACK,
            eatenAt = Instant.EPOCH,
            nutrients = Nutrients(
                energyKcal = 76.0,
                proteinG = 2.3,
                carbsG = 11.4,
                fatG = 2.3,
                saturatedFatG = 1.5,
                sugarG = 11.4,
                fiberG = 0.0,
                sodiumMg = 35.0,
            ),
        )

        fun factory(healthConnect: HealthConnectGateway) = viewModelFactory {
            initializer { SetupViewModel(healthConnect) }
        }
    }
}
