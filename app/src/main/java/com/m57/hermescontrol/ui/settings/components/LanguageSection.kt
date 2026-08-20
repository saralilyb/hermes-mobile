package com.m57.hermescontrol.ui.settings.components

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.util.LocaleContextWrapper

internal val SUPPORTED_LANGUAGE_CODES =
    listOf(
        LocaleContextWrapper.SYSTEM_LANGUAGE,
        "en",
        "zh",
        "ja",
        "ko",
    )

@Composable
internal fun supportedLanguages(): List<Pair<String, String>> =
    listOf(
        LocaleContextWrapper.SYSTEM_LANGUAGE to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "zh" to stringResource(R.string.language_chinese),
        "ja" to stringResource(R.string.language_japanese),
        "ko" to stringResource(R.string.language_korean),
    )

@Composable
internal fun languageLabel(code: String): String =
    supportedLanguages().firstOrNull { it.first == code }?.second
        ?: stringResource(R.string.language_system)

@Composable
internal fun LanguageSection(
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
    onRecreate: (() -> Unit)? = null,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.settings_item_language),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val activity = LocalActivity.current
        val recreateActivity = onRecreate ?: { activity?.recreate() }
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().testTag("language_picker"),
            ) {
                Text(languageLabel(appLanguage))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                supportedLanguages().forEach { (code, label) ->
                    DropdownMenuItem(
                        modifier =
                            Modifier
                                .testTag("language_option_$code")
                                .semantics {
                                    role = Role.RadioButton
                                    selected = code == appLanguage
                                },
                        text = { Text(label) },
                        trailingIcon = {
                            RadioButton(
                                selected = code == appLanguage,
                                onClick = null,
                            )
                        },
                        onClick = {
                            expanded = false
                            if (code != appLanguage) {
                                onAppLanguageChange(code)
                                recreateActivity()
                            }
                        },
                    )
                }
            }
        }
    }
}
