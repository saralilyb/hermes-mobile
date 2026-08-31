package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.theme.StatusGreen

@Composable
fun BotAvatar(
    name: String,
    avatar: BotAvatarMeta?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    isActive: Boolean = false,
) {
    val shape = remember(avatar?.shape, size) { resolveAvatarShape(avatar?.shape, size) }
    val fallbackColor = MaterialTheme.colorScheme.primaryContainer
    val backgroundColor = remember(avatar?.color, fallbackColor) { resolveAvatarColor(avatar?.color, fallbackColor) }
    val icon = remember(avatar?.icon) { resolveAvatarIcon(avatar?.icon) }

    Box(
        modifier = modifier.size(size).testTag("bot_avatar_$name"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clip(shape).background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = name,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(size * 0.55f),
                )
            } else {
                Text(
                    text = remember(name) { extractInitials(name) },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = (size.value * 0.4f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (isActive) {
            Box(
                Modifier
                    .size((size * 0.3f).coerceIn(8.dp, 14.dp))
                    .align(Alignment.BottomEnd)
                    .offset(1.dp, 1.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clip(CircleShape)
                    .background(StatusGreen),
            )
        }
    }
}

fun resolveAvatarShape(
    shapeKey: String?,
    size: Dp,
): Shape =
    when (shapeKey?.trim()?.lowercase()) {
        "square" -> RoundedCornerShape(size * 0.15f)
        "rounded" -> RoundedCornerShape(size * 0.32f)
        "hexagon" -> CutCornerShape(size * 0.25f)
        else -> CircleShape
    }

fun resolveAvatarIcon(iconKey: String?): ImageVector? =
    when (iconKey?.trim()?.lowercase()) {
        "code" -> Icons.Filled.Code
        "build" -> Icons.Filled.Build
        "psychology" -> Icons.Filled.Psychology
        "science" -> Icons.Filled.Science
        "bolt" -> Icons.Filled.Bolt
        "sensors" -> Icons.Filled.Sensors
        "extension" -> Icons.Filled.Extension
        "robot" -> Icons.Filled.SmartToy
        else -> null
    }

fun resolveAvatarColor(
    color: String?,
    fallback: Color,
): Color {
    if (color == null || !color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return fallback
    return runCatching { Color(0xFF000000L or color.drop(1).toLong(16)) }.getOrDefault(fallback)
}

fun extractInitials(name: String): String {
    val parts =
        name.trim().removePrefix("@").split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return if (parts.size > 1) {
        parts.take(2).joinToString("") { it.first().uppercase() }
    } else {
        parts.single().take(2).uppercase()
    }
}
