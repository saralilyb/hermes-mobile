package com.m57.hermescontrol.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
    val pagination: SessionMessagePagination? = null,
)

@Serializable
data class SessionMessagePagination(
    val limit: Int? = null,
    val offset: Int = 0,
    val returned: Int = 0,
    val total: Int? = null,
)

@Serializable
data class SessionMessage(
    val role: String? = null,
    @Serializable(with = JsonElementStringSerializer::class)
    val content: String? = null,
    val timestamp: JsonElement? = null,
    val type: String? = null,
    @Serializable(with = JsonElementStringSerializer::class)
    val reasoning: String? = null,
    @SerialName("reasoning_text")
    @Serializable(with = JsonElementStringSerializer::class)
    val legacyReasoningText: String? = null,
) {
    val timestampText: String?
        get() = (timestamp as? JsonPrimitive)?.content

    val contentText: String
        get() = content.orEmpty()

    val reasoningText: String
        get() = reasoning ?: legacyReasoningText.orEmpty()
}

internal object JsonElementStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Expected a JSON decoder")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonPrimitive -> {
                if (element.isString) element.content else element.toString()
            }
            else -> element.toString()
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: String?,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Expected a JSON encoder")
        jsonEncoder.encodeJsonElement(
            value?.let(::JsonPrimitive) ?: JsonNull,
        )
    }
}
