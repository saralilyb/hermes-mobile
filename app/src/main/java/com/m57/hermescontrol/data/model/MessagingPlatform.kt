package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class MessagingPlatform(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val configured: Boolean,
    @SerializedName("env_vars") val envVars: List<EnvVarField>? = null,
)

data class EnvVarField(
    val key: String,
    val required: Boolean,
    @SerializedName("is_set") val isSet: Boolean,
    val description: String?,
    val prompt: String?,
    @SerializedName("is_password") val isPassword: Boolean,
)

data class MessagingPlatformUpdate(
    val enabled: Boolean? = null,
    val env: Map<String, String> = emptyMap(),
    @SerializedName("clear_env") val clearEnv: List<String> = emptyList(),
    val profile: String? = null,
)
