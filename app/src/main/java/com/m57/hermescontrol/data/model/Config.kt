package com.m57.hermescontrol.data.model

data class RawConfigResponse(
    val path: String?,
    val yaml: String?,
)

data class UpdateRawConfigRequest(
    val yaml_text: String,
    val profile: String? = null,
)
