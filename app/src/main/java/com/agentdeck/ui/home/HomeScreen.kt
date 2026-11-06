package com.agentdeck.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentdeck.data.model.SiteCard

/**
 * The home screen: the site grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenCard: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingCard by remember { mutableStateOf<SiteCard?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Agent Deck") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // A new card starts from the defaults and is editable
                    // straight away.
                    editingCard = SiteCard(
                        id = "custom-${System.currentTimeMillis()}",
                        name = "新站点",
                        url = "https://",
                        region = com.agentdeck.data.model.SiteRegion.CN,
                        iconKey = "custom",
                        adapterId = "default",
                        sortOrder = 999,
                    )
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建卡片")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            SiteGrid(
                chinaSites = state.chinaSites,
                overseasSites = state.overseasSites,
                loginState = state.loginState,
                onCardClick = { card -> onOpenCard(card.id) },
                onCardLongClick = { card -> editingCard = card },
            )
        }
    }

    editingCard?.let { card ->
        SiteEditDialog(
            card = card,
            onDismiss = { editingCard = null },
            onSave = { updated ->
                viewModel.saveCard(updated)
                viewModel.refreshLoginState()
            },
            onDelete = if (card.id.startsWith("custom-")) {
                { viewModel.deleteCard(card.id) }
            } else {
                null
            },
        )
    }
}
