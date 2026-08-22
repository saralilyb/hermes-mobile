package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ModelOptionsResponse(
    val providers: List<ModelProvider>,
)

@Serializable
data class ModelProvider(
    val slug: String,
    val name: String,
    val is_current: Boolean? = null,
    val is_user_defined: Boolean? = null,
    val models: List<String>? = null,
    val total_models: Int? = null,
    val source: String? = null,
    val authenticated: Boolean? = null,
    val auth_type: String? = null,
    val warning: String? = null,
    val capabilities: Map<String, ModelCapabilities>? = null,
)

@Serializable
data class ModelCapabilities(
    val fast: Boolean? = null,
    val reasoning: Boolean? = null,
    val can_disable_reasoning: Boolean? = null,
)

fun List<ModelProvider>.capabilitiesFor(qualifiedModel: String?): ModelCapabilities? {
    if (qualifiedModel == null) return null

    val separator = qualifiedModel.indexOf('/')
    if (separator <= 0 || separator == qualifiedModel.lastIndex) return null

    val provider = qualifiedModel.substring(0, separator)
    val model = qualifiedModel.substring(separator + 1)
    return find { it.slug == provider }?.capabilities?.get(model)
}

@Serializable
data class PinnedModel(
    val providerSlug: String,
    val modelName: String,
)
