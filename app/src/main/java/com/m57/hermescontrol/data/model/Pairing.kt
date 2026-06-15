package com.m57.hermescontrol.data.model

data class PairingResponse(
    val pending: List<PairingItem>,
    val approved: List<PairingItem>,
)

data class PairingItem(
    val platform: String,
    val user_id: String?,
    val username: String?,
    val display_name: String?,
    val code: String?,
    val created_at: String?,
)

data class PairingApproveRequest(
    val platform: String,
    val code: String,
)

data class PairingRevokeRequest(
    val platform: String,
    val user_id: String,
)
