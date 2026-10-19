package com.m57.hermescontrol.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object LenientNullableBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor = PrimitiveSerialDescriptor("LegacyBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element == JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Invalid boolean")
        return primitive.booleanOrNull ?: when (primitive.intOrNull) {
            0 -> false
            1 -> true
            else -> throw SerializationException("Invalid boolean")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}
