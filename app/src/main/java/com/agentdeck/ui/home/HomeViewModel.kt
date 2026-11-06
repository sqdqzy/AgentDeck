package com.agentdeck.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.data.model.SiteCard
import com.agentdeck.data.model.SiteRegion
import com.agentdeck.data.prefs.SettingsStore
import com.agentdeck.data.repo.SiteRepository
import com.agentdeck.web.WebViewProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the home screen.
 */
data class HomeUiState(
    val chinaSites: List<SiteCard> = emptyList(),
    val overseasSites: List<SiteCard> = emptyList(),
    /** Login state per card id. */
    val loginState: Map<String, Boolean> = emptyMap(),
    val loading: Boolean = true,
)

/**
 * Drives the home grid.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val siteRepository: SiteRepository,
    private val profileManager: WebViewProfileManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _loginState = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Observable login state, refreshed whenever the cards change. */
    val loginState: StateFlow<Map<String, Boolean>> = _loginState.asStateFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        siteRepository.cards,
        _loginState,
    ) { cards, login ->
        HomeUiState(
            chinaSites = cards.filter { it.region == SiteRegion.CN },
            overseasSites = cards.filter { it.region == SiteRegion.OVERSEAS },
            loginState = login,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            siteRepository.seedIfEmpty()
        }
        viewModelScope.launch {
            siteRepository.cards.collect { cards ->
                refreshLoginState(cards)
            }
        }
    }

    /** Recomputes the login dots from the isolated profiles. */
    fun refreshLoginState(cards: List<SiteCard> = emptyList()) {
        viewModelScope.launch {
            val source = if (cards.isEmpty()) {
                siteRepository.cards.first()
            } else {
                cards
            }
            _loginState.value = source.associate { card ->
                card.id to profileManager.hasSession(card.profileName, card.url)
            }
        }
    }

    /** Saves an edited card. */
    fun saveCard(card: SiteCard) {
        viewModelScope.launch { siteRepository.saveCard(card) }
    }

    /** Deletes a card. */
    fun deleteCard(id: String) {
        viewModelScope.launch { siteRepository.deleteCard(id) }
    }
}
