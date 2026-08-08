package com.m57.hermescontrol.notification

internal const val EXTRA_NOTIFICATION_SESSION_ID =
    "extra_notification_session_id"
internal const val EXTRA_NOTIFICATION_PROFILE_ID =
    "extra_notification_profile_id"

private const val OPEN_CHAT_ACTION_SUFFIX =
    ".ACTION_OPEN_CHAT_FROM_NOTIFICATION"

internal fun openChatAction(appId: String) = "$appId$OPEN_CHAT_ACTION_SUFFIX"

internal sealed interface NotificationEntryDecision {
    data class OpenChat(
        val sessionId: String,
        val profileId: String,
    ) : NotificationEntryDecision

    data object OpenApp : NotificationEntryDecision

    data object Reject : NotificationEntryDecision
}

internal fun resolveNotificationEntry(
    action: String?,
    applicationId: String,
    sessionId: String?,
    sourceProfileId: String?,
    activeProfileId: String?,
): NotificationEntryDecision {
    if (action != openChatAction(applicationId)) {
        return NotificationEntryDecision.Reject
    }

    val targetSessionId =
        sessionId?.takeIf { it.isNotBlank() }
            ?: return NotificationEntryDecision.OpenApp
    val sourceProfile =
        sourceProfileId?.takeIf { it.isNotBlank() }
            ?: return NotificationEntryDecision.OpenApp
    val activeProfile =
        activeProfileId?.takeIf { it.isNotBlank() }
            ?: return NotificationEntryDecision.OpenApp

    if (sourceProfile != activeProfile) {
        return NotificationEntryDecision.OpenApp
    }

    return NotificationEntryDecision.OpenChat(
        sessionId = targetSessionId,
        profileId = sourceProfile,
    )
}
