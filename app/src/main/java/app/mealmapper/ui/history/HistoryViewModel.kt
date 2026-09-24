package app.mealmapper.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.AppContainer
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.domain.MealSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val items: StateFlow<List<LoggedItem>> = c.log.items

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /** Same client id, newer version: Health Connect replaces the record, so Google Health shows the new values. */
    fun update(item: LoggedItem, name: String, amount: Double, slot: MealSlot) {
        val changed = item.copy(name = name.trim().ifEmpty { item.name }, amount = amount, slot = slot.name)
        viewModelScope.launch {
            runCatching { c.healthConnect.write(changed.toEntry()) }
                .onSuccess {
                    c.log.replace(changed)
                    _message.value = "Updated: ${changed.name}, ${Math.round(changed.nutrients.energyKcal)} kcal"
                }
                .onFailure { _message.value = "Not updated. Health Connect said: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    fun delete(item: LoggedItem) {
        viewModelScope.launch {
            runCatching { c.healthConnect.delete(item.clientId) }
                .onSuccess {
                    c.log.remove(item.clientId)
                    _message.value = "Deleted: ${item.name}"
                }
                .onFailure { _message.value = "Not deleted. Health Connect said: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    fun toggleFavourite(item: LoggedItem) = c.log.toggleFavourite(item.name)

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { HistoryViewModel(c) } }
    }
}
