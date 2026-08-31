package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: BotsViewModel = viewModel { BotsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedProfileId by AuthManager.selectedProfileIdFlow.collectAsStateWithLifecycle()

    LaunchedEffect(selectedProfileId, state.sourceConnectionProfileId) {
        val sourceProfileId = state.sourceConnectionProfileId
        if (sourceProfileId != null && sourceProfileId != selectedProfileId && !state.isLoading) {
            viewModel.loadBots()
        }
    }

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_bots)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        actions = {
            if (state.hasHiddenBots) {
                IconButton(onClick = viewModel::toggleShowHidden) {
                    Icon(
                        if (state.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.bots_toggle_hidden),
                    )
                }
            }
        },
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.loadBots(isRefresh = true) },
    ) { paddingValues ->
        when {
            state.isLoading && state.profiles.isEmpty() -> LoadingState(Modifier.padding(paddingValues))
            state.errorMessage != null && state.profiles.isEmpty() ->
                ErrorState(state.errorMessage.orEmpty(), onRetry = viewModel::loadBots)
            state.displayProfiles.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.bots_empty_title),
                    subtitle = stringResource(R.string.bots_empty_description),
                )
            else ->
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.bots_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.displayProfiles) { profile ->
                            val canOpen = canOpenBot(profile, state.sourceConnectionProfileId, selectedProfileId)
                            BotCard(
                                profile = profile,
                                isActive = profile.isActiveAt(state.nowSeconds),
                                onClick =
                                    if (canOpen) {
                                        { NavigationController.openBot(profile, state.sourceConnectionProfileId) }
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                }
        }
    }
}

internal fun canOpenBot(
    profile: ProfileInfo,
    sourceConnectionProfileId: String?,
    selectedConnectionProfileId: String?,
): Boolean =
    profile.canonicalSessionId != null &&
        !sourceConnectionProfileId.isNullOrBlank() &&
        sourceConnectionProfileId == selectedConnectionProfileId

@Composable
private fun BotCard(
    profile: ProfileInfo,
    isActive: Boolean,
    onClick: (() -> Unit)?,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(
                name = profile.name,
                avatar = profile.botMeta()?.avatar,
                size = 44.dp,
                isActive = isActive,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.effectiveTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.effectiveTitle != profile.name) {
                    Text("@${profile.name}", style = MaterialTheme.typography.labelSmall)
                }
                if (profile.effectiveDescription.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        profile.effectiveDescription,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
