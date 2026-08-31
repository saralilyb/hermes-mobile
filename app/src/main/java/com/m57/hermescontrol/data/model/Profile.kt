package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class ProfilesResponse(
    val profiles: List<ProfileInfo>,
    val bot_mode_protocol: Boolean? = null,
)

@Serializable
data class ProfileInfo(
    val name: String,
    val path: String? = null,
    val is_default: Boolean? = null,
    val model: String? = null,
    val provider: String? = null,
    val has_env: Boolean? = null,
    val skill_count: Int? = null,
    val gateway_running: Boolean? = null,
    val description: String? = null,
    val display_name: String? = null,
    val description_auto: Boolean? = null,
    val ui_meta: Map<String, JsonElement>? = null,
    val canonical_session: CanonicalSessionInfo? = null,
    val last_session: ProfileSessionSummary? = null,
    val worker_session: ProfileWorkerSummary? = null,
) {
    fun botMeta(json: Json = BOT_METADATA_JSON): BotRosterMeta? {
        val element = ui_meta?.get(BOT_METADATA_NAMESPACE) ?: return null
        return runCatching { json.decodeFromJsonElement<BotRosterMeta>(element) }.getOrNull()
    }

    val effectiveTitle: String
        get() = botMeta()?.title?.takeIf { it.isNotBlank() } ?: display_name?.takeIf { it.isNotBlank() } ?: name

    val effectiveDescription: String
        get() = botMeta()?.description?.takeIf { it.isNotBlank() } ?: description.orEmpty()

    val isHidden: Boolean
        get() = botMeta()?.hidden == true

    val canonicalSessionId: String?
        get() =
            canonical_session?.resolved_id?.takeIf { it.isNotBlank() }
                ?: canonical_session?.id?.takeIf { it.isNotBlank() }
}

private const val BOT_METADATA_NAMESPACE = "hermes-bots"
private val BOT_METADATA_JSON = Json { ignoreUnknownKeys = true }

@Serializable
data class CanonicalSessionInfo(
    val id: String,
    val resolved_id: String? = null,
    val last_active: Long? = null,
)

@Serializable
data class ProfileSessionSummary(
    val id: String,
    val last_active: Long? = null,
)

@Serializable
data class ProfileWorkerSummary(
    val id: String,
    val last_active: Long? = null,
)

@Serializable
data class BotRosterMeta(
    val title: String? = null,
    val description: String? = null,
    val avatar: BotAvatarMeta? = null,
    val hidden: Boolean? = null,
)

@Serializable
data class BotAvatarMeta(
    val shape: String? = null,
    val color: String? = null,
    val icon: String? = null,
)

@Serializable
data class ActiveProfileResponse(
    val active: String,
    val current: String? = null,
)

@Serializable
data class SetActiveProfileRequest(
    val name: String,
)

@Serializable
data class ProfileSoulResponse(
    val content: String,
)

@Serializable
data class UpdateProfileSoulRequest(
    val content: String,
)

@Serializable
data class UpdateProfileModelRequest(
    val provider: String,
    val model: String,
)

@Serializable
data class UpdateProfileDescriptionRequest(
    val description: String,
)

@Serializable
data class CreateProfileRequest(
    val name: String,
    val description: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val mcp_servers: List<McpServerConfigInput>? = null,
    val keep_skills: Boolean? = null,
    val hub_skills: List<String>? = null,
    val clone_from: String? = null,
    val clone_all: Boolean? = null,
    val clone_from_default: Boolean? = null,
)

@Serializable
data class McpServerConfigInput(
    val name: String,
    val transport: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
)
