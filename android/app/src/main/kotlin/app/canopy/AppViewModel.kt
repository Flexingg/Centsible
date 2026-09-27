package app.canopy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.data.CapabilitiesRepository
import app.canopy.core.domain.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Starting : AppState
    data object NeedsPairing : AppState
    data object NeedsBudget : AppState
    data object Ready : AppState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    sessions: SessionStore,
    private val capabilities: CapabilitiesRepository,
) : ViewModel() {
    val state: StateFlow<AppState> = sessions.session
        .onEach { if (it?.selectedBudget != null) viewModelScope.launch { capabilities.refresh() } }
        .map {
            when {
                it == null -> AppState.NeedsPairing
                it.selectedBudget == null -> AppState.NeedsBudget
                else -> AppState.Ready
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Starting)
}
