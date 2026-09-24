package app.mealmapper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.AppContainer
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.domain.DayTotals
import app.mealmapper.domain.mealSlotFor
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** One-tap logging on Home: starred foods first, then the most recent other foods. */
data class QuickLog(val favourites: List<LoggedItem>, val recent: List<LoggedItem>)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val healthConnect = c.healthConnect
    private val profile = c.profile

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val quick: StateFlow<QuickLog> = c.log.items.map { all ->
        // Newest entry per food name carries the amount to repeat.
        val latest = all.distinctBy { it.name }
        QuickLog(latest.filter { it.favourite }.take(8), latest.filter { !it.favourite }.take(5))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickLog(emptyList(), emptyList()))

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var lastLogged: LoggedItem? = null

    fun messageShown() {
        _message.value = null
    }

    /** Same food, same amount, now. */
    fun logAgain(item: LoggedItem) {
        val now = Instant.now()
        val again = item.copy(
            clientId = UUID.randomUUID().toString(),
            eatenAt = now.epochSecond,
            slot = mealSlotFor(LocalTime.now()).name,
        )
        viewModelScope.launch {
            runCatching { healthConnect.write(again.toEntry()) }
                .onSuccess {
                    c.log.add(again)
                    lastLogged = again
                    _message.value = "Logged: ${again.name}, ${Math.round(again.nutrients.energyKcal)} kcal"
                    refresh()
                }
                .onFailure { _message.value = "Not logged. Health Connect said: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    fun undoLast() {
        val item = lastLogged ?: return
        lastLogged = null
        viewModelScope.launch {
            runCatching { healthConnect.delete(item.clientId) }.onSuccess {
                c.log.remove(item.clientId)
                refresh()
            }
        }
    }

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
        fun factory(c: AppContainer) = viewModelFactory { initializer { HomeViewModel(c) } }
    }
}
