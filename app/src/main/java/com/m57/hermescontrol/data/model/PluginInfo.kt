package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class PluginInfo(
    val name: String,
    val description: String?,
    val version: String?,
    val enabled: Boolean,
    val installed: Boolean = true,
)

data class TogglePluginRequest(
    val enabled: Boolean,
)

data class AgentPluginInstallBody(
    val identifier: String,
    val force: Boolean = false,
    val enable: Boolean = true,
)
