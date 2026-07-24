package com.m57.hermescontrol.ui.keys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

private const val LEGACY_SENSITIVE_CLIP_KEY =
    "android.content.extra.IS_SENSITIVE"

internal fun sensitiveClipboardExtraKey(sdkInt: Int = Build.VERSION.SDK_INT): String =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        LEGACY_SENSITIVE_CLIP_KEY
    }

internal fun createSensitiveClip(
    label: String,
    text: String,
): ClipData =
    ClipData.newPlainText(label, text).apply {
        description.extras =
            PersistableBundle().apply {
                putBoolean(sensitiveClipboardExtraKey(), true)
            }
    }

internal fun copySensitiveText(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard =
        requireNotNull(context.getSystemService(ClipboardManager::class.java))
    clipboard.setPrimaryClip(createSensitiveClip(label, text))
}
