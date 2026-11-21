package network.orion.data.memory

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import network.orion.core.model.OrionMemoryDetails
import network.orion.core.security.FieldEncryptionContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object OrionMemoryDetailsCodec {
    fun context(id: UUID) = FieldEncryptionContext("memory/$id", "details", 1)

    fun encode(details: OrionMemoryDetails): ByteArray = JSONObject()
        .put("source", details.source ?: JSONObject.NULL)
        .put("summary", details.summary ?: JSONObject.NULL)
        .put("tags", JSONArray(details.tags))
        .toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): OrionMemoryDetails? {
        if (bytes.size !in 1..24_576) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JSONObject(text)
            if (json.length() != 3 || !json.has("source") || !json.has("summary")) return null
            val tags = json.getJSONArray("tags")
            if (tags.length() > 16) return null
            OrionMemoryDetails(json.nullableText("source"), json.nullableText("summary"),
                List(tags.length()) { index -> tags.get(index) as? String ?: throw JSONException("Invalid memory tag.") })
        } catch (_: JSONException) {
            null
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JSONObject.nullableText(key: String): String? =
        if (isNull(key)) null else get(key) as? String ?: throw JSONException("Invalid memory details field.")
}
