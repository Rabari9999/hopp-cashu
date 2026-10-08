package space.sovereigncast.hopp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase

/** The listener's settings, with the defaults of ADR 0012. */
data class Settings(
    val skipBackSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    /** Android only (ADR 0012); iOS ignores it. */
    val skipSilence: Boolean = false,
    /** Space for downloads; 0 = no limit. */
    val storageLimitMb: Int = 2_048,
    /** Extra directories, off by default — each sees the searches (ADR 0008). */
    val fyydSearch: Boolean = false,
    val appleSearch: Boolean = false,
    /** The listener's own Podcast Index key; empty = the one in the app. */
    val podcastIndexKey: String = "",
    val podcastIndexSecret: String = "",
) {
    companion object {
        val skipBackChoices = listOf(5, 10, 15, 30)
        val skipForwardChoices = listOf(10, 15, 30, 45, 60)
        val storageChoicesMb = listOf(1_024, 2_048, 5_120, 10_240, 0)

        internal fun from(values: Map<String, String>): Settings {
            val d = Settings()
            return Settings(
                skipBackSeconds = values["skip_back"]?.toIntOrNull() ?: d.skipBackSeconds,
                skipForwardSeconds = values["skip_forward"]?.toIntOrNull() ?: d.skipForwardSeconds,
                skipSilence = values["skip_silence"]?.toBooleanStrictOrNull() ?: d.skipSilence,
                storageLimitMb = values["storage_limit_mb"]?.toIntOrNull() ?: d.storageLimitMb,
                fyydSearch = values["fyyd_search"]?.toBooleanStrictOrNull() ?: d.fyydSearch,
                appleSearch = values["apple_search"]?.toBooleanStrictOrNull() ?: d.appleSearch,
                podcastIndexKey = values["podcastindex_key"] ?: d.podcastIndexKey,
                podcastIndexSecret = values["podcastindex_secret"] ?: d.podcastIndexSecret,
            )
        }
    }

    internal fun toMap() = mapOf(
        "skip_back" to skipBackSeconds.toString(),
        "skip_forward" to skipForwardSeconds.toString(),
        "skip_silence" to skipSilence.toString(),
        "storage_limit_mb" to storageLimitMb.toString(),
        "fyyd_search" to fyydSearch.toString(),
        "apple_search" to appleSearch.toString(),
        "podcastindex_key" to podcastIndexKey,
        "podcastindex_secret" to podcastIndexSecret,
    )
}

class SettingsRepository(private val db: HoppDatabase) {
    val settings: Flow<Settings> = db.settingQueries.selectAll().asFlow().mapToList(Dispatchers.IO)
        .map { rows -> Settings.from(rows.associate { it.key to it.value_ }) }

    suspend fun current(): Settings = settings.first()

    suspend fun update(change: (Settings) -> Settings) = withContext(Dispatchers.IO) {
        val before = Settings.from(db.settingQueries.selectAll().executeAsList().associate { it.key to it.value_ })
        val after = change(before)
        db.transaction { after.toMap().forEach { (k, v) -> if (before.toMap()[k] != v) db.settingQueries.put(k, v) } }
    }
}
