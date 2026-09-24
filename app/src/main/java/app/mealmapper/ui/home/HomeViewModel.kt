package app.mealmapper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.data.settings.ProfileStore
import app.mealmapper.domain.DayTotals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    /** null while checking. */
    val healthReady: Boolean? = null,
    val totals: DayTotals? = null,
    val capKcal: Int? = null,
    val totalsError: Boolean = false,
)

class HomeViewModel(
    private val healthConnect: HealthConnectGateway,
    private val profile: ProfileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** Called on every return to Home: after a save, after Settings, after Health Connect changes. */
    fun refresh() {
        _state.update { it.copy(capKcal = profile.profile.value.dailyCapKcal) }
        viewModelScope.launch {
            val ready = healthConnect.availability() == HealthConnectAvailability.AVAILABLE &&
                runCatching { healthConnect.hasAllPermissions() }.getOrDefault(false)
            if (!ready) {
                _state.update { it.copy(healthReady = false, totals = null) }
                return@launch
            }
            val totals = runCatching { healthConnect.todayTotals() }.getOrNull()
            _state.update { it.copy(healthReady = true, totals = totals, totalsError = totals == null) }
        }
    }

    companion object {
        fun factory(healthConnect: HealthConnectGateway, profile: ProfileStore) =
            viewModelFactory { initializer { HomeViewModel(healthConnect, profile) } }
    }
}
