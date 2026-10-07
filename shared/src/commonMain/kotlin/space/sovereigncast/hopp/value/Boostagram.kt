package space.sovereigncast.hopp.value

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.sovereigncast.hopp.crypto.toHex

/** A keysend TLV record: type and hex-encoded value. */
data class TlvRecord(val type: Long, val valueHex: String)

/**
 * Boost metadata per bLIP-10, record 7629169: a JSON object travelling with a
 * keysend payment. Carries podcast and episode GUIDs, which is what
 * Sovereigncast's invoice→episode auto-linking keys on (ADR 0005).
 */
data class Boostagram(
    val action: String, // "boost" or "stream"
    val appName: String,
    val appVersion: String,
    val podcast: String?,
    val episode: String?,
    val feedUrl: String?,
    val podcastGuid: String?,
    val episodeGuid: String?,
    val positionSeconds: Long?,
    val valueMsat: Long,
    val valueMsatTotal: Long,
    val recipientName: String?,
    val senderName: String? = null,
    val message: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        put("app_name", appName)
        put("app_version", appVersion)
        podcast?.let { put("podcast", it) }
        episode?.let { put("episode", it) }
        feedUrl?.let { put("url", it) }
        podcastGuid?.let { put("guid", it) }
        episodeGuid?.let { put("episode_guid", it) }
        positionSeconds?.let { put("ts", it) }
        put("value_msat", valueMsat)
        put("value_msat_total", valueMsatTotal)
        recipientName?.let { put("name", it) }
        senderName?.takeIf { it.isNotBlank() }?.let { put("sender_name", it) }
        message?.takeIf { it.isNotBlank() }?.let { put("message", it) }
    }

    companion object {
        const val TLV_BOOSTAGRAM = 7_629_169L
    }
}

/** The TLV records for one recipient's keysend: the boostagram plus the
 *  recipient's own customKey/customValue (e.g. Nodesignal's 696969). */
fun keysendRecords(boostagram: Boostagram, customKey: String?, customValue: String?): List<TlvRecord> = buildList {
    add(TlvRecord(Boostagram.TLV_BOOSTAGRAM, boostagram.toJson().toString().encodeToByteArray().toHex()))
    val key = customKey?.toLongOrNull()
    if (key != null && !customValue.isNullOrEmpty()) add(TlvRecord(key, customValue.encodeToByteArray().toHex()))
}
