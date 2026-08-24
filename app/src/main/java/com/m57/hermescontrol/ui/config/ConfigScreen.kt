package com.m57.hermescontrol.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.ExposedDropdownField
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ConfigViewModel = viewModel { ConfigViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ToastEffect(
        toastMessage = state.toastMessage?.let { stringResource(it.resourceId, *it.args.toTypedArray()) },
        onClearToast = viewModel::clearToast,
    )

    HermesScaffold(
        title = { Text(stringResource(R.string.config_screen_title)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAll() },
        actions = {
            if (!state.yamlMode && state.modifiedKeys.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.saveConfig() },
                    enabled = !state.isSaving && state.invalidKeys.isEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = stringResource(R.string.config_save_changes),
                    )
                }
            }
        },
    ) { paddingValues ->
        when {
            state.isLoading && state.values == null -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.values == null -> {
                ErrorState(
                    message = stringResource(R.string.config_load_failed, state.errorMessage.orEmpty()),
                    onRetry = { viewModel.loadAll() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                ConfigContent(
                    state = state,
                    onModeToggle = viewModel::toggleYamlMode,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    onCategoryChange = viewModel::setActiveCategory,
                    onFieldChange = viewModel::updateField,
                    onFieldDraftChange = viewModel::setFieldDraft,
                    onFieldDraftClear = viewModel::clearFieldDraft,
                    onResetField = viewModel::resetField,
                    onClearField = viewModel::clearField,
                    onSave = viewModel::saveConfig,
                    onYamlTextChange = viewModel::setYamlText,
                    onYamlSave = viewModel::saveYamlConfig,
                    onYamlRetry = viewModel::loadYaml,
                    onResetCategory = viewModel::resetCategoryToDefaults,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ConfigContent(
    state: ConfigUiState,
    onModeToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onFieldChange: (String, JsonElement) -> Unit,
    onFieldDraftChange: (String, String, Boolean) -> Unit,
    onFieldDraftClear: (String) -> Unit,
    onResetField: (String) -> Unit,
    onClearField: (String) -> Unit,
    onSave: () -> Unit,
    onYamlTextChange: (String) -> Unit,
    onYamlSave: () -> Unit,
    onYamlRetry: () -> Unit,
    onResetCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // ── Non-scrollable top section ──
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
        ) {
            // Path display
            state.path?.let { path ->
                Text(
                    text = stringResource(R.string.config_path, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Mode toggle + search
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onModeToggle,
                    enabled = canSwitchConfigMode(state.isSaving, state.yamlIsSaving),
                ) {
                    Icon(
                        imageVector = if (state.yamlMode) Icons.Filled.Tune else Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        stringResource(if (state.yamlMode) R.string.config_mode_form else R.string.config_mode_yaml),
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (!state.yamlMode) {
                    SearchBar(
                        query = state.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.config_search_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Top save bar — sticky, always visible when changes pending
            if (!state.yamlMode && state.modifiedKeys.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.config_pending_changes, state.modifiedKeys.size),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = onSave,
                            enabled = !state.isSaving && state.invalidKeys.isEmpty(),
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.width(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.config_action_save),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Scrollable content ──
        if (state.yamlMode) {
            YAMLEditor(
                yamlText = state.yamlText,
                onYamlTextChange = onYamlTextChange,
                isSaving = state.yamlIsSaving,
                isLoading = state.yamlIsLoading,
                loadError = state.yamlLoadError,
                onSave = onYamlSave,
                onRetry = onYamlRetry,
            )
        } else {
            FormEditor(
                values = state.values,
                schema = state.schema,
                defaults = state.defaults,
                uncoveredPaths = state.uncoveredPaths,
                activeCategory = state.activeCategory,
                searchQuery = state.searchQuery,
                modifiedKeys = state.modifiedKeys,
                invalidKeys = state.invalidKeys,
                fieldDrafts = state.fieldDrafts,
                isSaving = state.isSaving,
                onCategoryChange = onCategoryChange,
                onFieldChange = onFieldChange,
                onFieldDraftChange = onFieldDraftChange,
                onFieldDraftClear = onFieldDraftClear,
                onResetField = onResetField,
                onClearField = onClearField,
                onSave = onSave,
                onResetCategory = onResetCategory,
            )
        }
    }
}

@Composable
private fun YAMLEditor(
    yamlText: String?,
    onYamlTextChange: (String) -> Unit,
    isSaving: Boolean,
    isLoading: Boolean,
    loadError: String?,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    if (isLoading) {
        SkeletonListState()
        return
    }

    if (loadError != null) {
        ErrorState(
            message = stringResource(R.string.config_load_yaml_failed, loadError),
            onRetry = onRetry,
        )
        return
    }

    if (!isYamlDocumentEditable(yamlText, isLoading, loadError)) return
    val loadedYaml = yamlText ?: return

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = loadedYaml,
            onValueChange = onYamlTextChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text(stringResource(R.string.config_yaml_editor_label)) },
            maxLines = Int.MAX_VALUE,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving,
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    stringResource(R.string.config_action_save_yaml),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun FormEditor(
    values: Map<String, JsonElement>?,
    schema: ConfigSchemaResponse?,
    defaults: Map<String, JsonElement>?,
    uncoveredPaths: List<String>,
    activeCategory: String,
    searchQuery: String,
    modifiedKeys: Set<String>,
    invalidKeys: Set<String>,
    fieldDrafts: Map<String, ConfigFieldDraft>,
    isSaving: Boolean,
    onCategoryChange: (String) -> Unit,
    onFieldChange: (String, JsonElement) -> Unit,
    onFieldDraftChange: (String, String, Boolean) -> Unit,
    onFieldDraftClear: (String) -> Unit,
    onResetField: (String) -> Unit,
    onClearField: (String) -> Unit,
    onSave: () -> Unit,
    onResetCategory: (String) -> Unit,
) {
    if (schema == null || values == null) return

    val isSearching = searchQuery.isNotBlank()
    val otherCategory = remember { orderedCategories(emptyList(), emptyList(), true).single() }
    val categoryCounts =
        remember(schema, uncoveredPaths) {
            schema.fields.values
                .groupingBy { it.category ?: "general" }
                .eachCount() + (otherCategory to uncoveredPaths.size)
        }

    // Every row is a flat dot-path with an O(1) value lookup — no nested walks.
    val rows: List<ConfigRow> =
        remember(values, schema, uncoveredPaths) {
            val covered =
                schema.fields.map { (key, field) ->
                    ConfigRow(key = key, field = field, value = values[key])
                }
            val uncovered =
                uncoveredPaths.map { dotPath ->
                    ConfigRow(key = dotPath, field = null, value = values[dotPath])
                }
            covered + uncovered
        }

    val visibleRows: List<ConfigRow> =
        remember(rows, activeCategory, searchQuery) {
            if (isSearching) {
                rows.filter { rowMatchesQuery(it, searchQuery) }
            } else {
                rows.filter { row ->
                    if (isSyntheticOtherCategory(activeCategory)) {
                        row.isUncovered
                    } else {
                        !row.isUncovered && row.category == activeCategory
                    }
                }
            }
        }

    val allCategories =
        remember(schema, uncoveredPaths, categoryCounts) {
            orderedCategories(
                categoryOrder = schema.category_order,
                schemaCategories = categoryCounts.keys - otherCategory,
                hasUncoveredPaths = uncoveredPaths.isNotEmpty(),
            )
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        // Category tabs — hidden while searching
        if (!isSearching) {
            item(key = "tabs") {
                ConfigTabs(
                    categories = allCategories,
                    categoryCounts = categoryCounts,
                    selectedCategory = activeCategory,
                    onCategorySelected = onCategoryChange,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        } else {
            item(key = "search-meta") {
                val count = visibleRows.size
                Text(
                    text = stringResource(R.string.config_search_results, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        if (isSyntheticOtherCategory(activeCategory) && !isSearching) {
            // Non-schema config values — read-only cards
            items(uncoveredPaths, key = { "uncovered:$it" }) { dotPath ->
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = dotPath,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = values[dotPath]?.let(::jsonText) ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(visibleRows, key = { "field:${it.key}" }) { row ->
                ConfigFieldCard(
                    row = row,
                    defaultValue = defaults?.get(row.key),
                    isModified = row.key in modifiedKeys,
                    draft = fieldDrafts[row.key],
                    showCategoryChip = isSearching,
                    onChange = { onFieldChange(row.key, it) },
                    onDraftChange = { text, valid -> onFieldDraftChange(row.key, text, valid) },
                    onDraftClear = { onFieldDraftClear(row.key) },
                    onReset = { onResetField(row.key) },
                    onClear = { onClearField(row.key) },
                )
            }

            if (visibleRows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text =
                            if (isSearching) {
                                stringResource(R.string.config_no_search_results)
                            } else {
                                stringResource(R.string.config_no_category_fields)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }

        // Save + Reset buttons
        item(key = "actions") {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = modifiedKeys.isNotEmpty() && !isSaving && invalidKeys.isEmpty(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(stringResource(R.string.config_action_save))
                    }
                }

                if (!isSearching) {
                    OutlinedButton(
                        onClick = { onResetCategory(activeCategory) },
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(stringResource(R.string.config_action_reset))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTabs(
    categories: List<String>,
    categoryCounts: Map<String, Int>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
        edgePadding = 0.dp,
    ) {
        categories.forEach { category ->
            val count = categoryCounts[category] ?: 0
            Tab(
                selected = category == selectedCategory,
                onClick = { onCategorySelected(category) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text =
                                if (isSyntheticOtherCategory(category)) {
                                    stringResource(R.string.config_category_other)
                                } else {
                                    category.replaceFirstChar { it.uppercase() }
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Badge { Text(count.toString()) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ConfigFieldCard(
    row: ConfigRow,
    defaultValue: JsonElement?,
    isModified: Boolean,
    draft: ConfigFieldDraft?,
    showCategoryChip: Boolean,
    onChange: (JsonElement) -> Unit,
    onDraftChange: (String, Boolean) -> Unit,
    onDraftClear: () -> Unit,
    onReset: () -> Unit,
    onClear: () -> Unit,
) {
    val field = row.field
    val description =
        field?.description
            ?: row.key.replace(".", " → ").replace("_", " ").replaceFirstChar { it.uppercase() }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isModified) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header row: category chip (searching), label, actions ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCategoryChip) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = row.category.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isModified) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (isModified) {
                    Box(
                        modifier =
                            Modifier
                                .padding(start = 6.dp)
                                .size(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {}
                    }
                }

                if (defaultValue != null && defaultValue != row.value) {
                    CompactIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.config_reset_default),
                        onClick = onReset,
                    )
                }

                if (field?.clearable == true && row.valueText.isNotEmpty()) {
                    CompactIconButton(
                        icon = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.config_clear_value),
                        onClick = onClear,
                    )
                }
            }

            if (field == null) {
                // Uncovered path reached via search — show its value read-only
                Text(
                    text = row.valueText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                return@Column
            }

            if (field.type.isNotEmpty() && field.type != "string") {
                Text(
                    text = field.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            when (field.type) {
                "boolean" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = (row.value as? JsonPrimitive)?.booleanOrNull ?: false,
                            onCheckedChange = { onChange(JsonPrimitive(it)) },
                        )
                    }
                }

                "select" -> {
                    if (field.searchable) {
                        SearchableSelectField(
                            key = row.key,
                            label = row.label,
                            options = field.options ?: emptyList(),
                            selectedValue = (row.value as? JsonPrimitive)?.content ?: "",
                            onOptionSelected = { onChange(JsonPrimitive(it)) },
                        )
                    } else {
                        ExposedDropdownField(
                            label = row.label,
                            options = field.options ?: emptyList(),
                            selectedValue = (row.value as? JsonPrimitive)?.content ?: "",
                            onOptionSelected = { onChange(JsonPrimitive(it)) },
                        )
                    }
                    if (description != row.label) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                "number" -> {
                    NumberField(
                        value = row.value,
                        draft = draft,
                        label = description,
                        onChange = onChange,
                        onDraftChange = onDraftChange,
                        onDraftClear = onDraftClear,
                    )
                }

                "object", "list" -> {
                    JsonField(
                        value = row.value,
                        draft = draft,
                        label = description,
                        schemaType = field.type,
                        onChange = onChange,
                        onDraftChange = onDraftChange,
                        onDraftClear = onDraftClear,
                    )
                }

                else -> {
                    // String
                    OutlinedTextField(
                        value = (row.value as? JsonPrimitive)?.content ?: "",
                        onValueChange = { onChange(JsonPrimitive(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(description) },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        }
    }
}

/** Compact header action icon (reset / clear). */
@Composable
private fun CompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Searchable dropdown (schema `searchable: true`, e.g. timezone's 590 options).
 * Typing filters the menu; only a picked option (or an exact-match on blur)
 * commits — closed-world, matching the desktop SearchableSelect. The first
 * keystroke after focus starts a FRESH filter (the existing value's prefix is
 * stripped) so typing "amman" over "Asia/Amman" doesn't append to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableSelectField(
    key: String,
    label: String,
    options: List<String>,
    selectedValue: String,
    onOptionSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(key) { mutableStateOf(selectedValue) }
    var hasFocus by remember { mutableStateOf(false) }
    var untouched by remember(key) { mutableStateOf(true) }

    LaunchedEffect(selectedValue) {
        if (!hasFocus) {
            query = selectedValue
            untouched = true
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { newText ->
                if (untouched && query == selectedValue && newText.startsWith(selectedValue)) {
                    // Fresh filter over a pre-filled value: drop the old value
                    query = newText.removePrefix(selectedValue)
                } else {
                    query = newText
                }
                untouched = false
                expanded = true
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .onFocusChanged {
                        hasFocus = it.isFocused
                        if (!it.isFocused) {
                            untouched = true
                            if (query in options && query != selectedValue) {
                                onOptionSelected(query)
                            } else {
                                query = selectedValue
                            }
                        }
                    },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            val filtered = options.filter { it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.config_no_matches),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                )
            } else {
                filtered.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            query = option
                            onOptionSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Number editor whose draft is owned above the lazy viewport. */
@Composable
private fun NumberField(
    value: JsonElement?,
    draft: ConfigFieldDraft?,
    label: String,
    onChange: (JsonElement) -> Unit,
    onDraftChange: (String, Boolean) -> Unit,
    onDraftClear: () -> Unit,
) {
    val text = draft?.text ?: (value as? JsonPrimitive)?.content.orEmpty()

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            val parsed = parseFiniteNumber(newText)
            onDraftChange(newText, parsed != null)
            parsed?.let(onChange)
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (!it.isFocused && parseFiniteNumber(text) != null) onDraftClear()
                },
        isError = parseFiniteNumber(text) == null,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
    )
}

/** Object/list JSON editor whose draft is owned above the lazy viewport. */
@Composable
private fun JsonField(
    value: JsonElement?,
    draft: ConfigFieldDraft?,
    label: String,
    schemaType: String,
    onChange: (JsonElement) -> Unit,
    onDraftChange: (String, Boolean) -> Unit,
    onDraftClear: () -> Unit,
) {
    val text = draft?.text ?: value?.let(::jsonText).orEmpty()
    val invalid = draft?.isValid == false

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            val parsed = parseStructuredJson(newText, schemaType)
            onDraftChange(newText, parsed != null)
            if (parsed != null && newText.isNotBlank()) onChange(parsed)
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .onFocusChanged {
                    if (!it.isFocused && !invalid) onDraftClear()
                },
        isError = invalid,
        supportingText =
            if (invalid) {
                { Text(stringResource(R.string.config_invalid_json)) }
            } else {
                null
            },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        label = { Text(label) },
        maxLines = Int.MAX_VALUE,
    )
}
