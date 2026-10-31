package space.sovereigncast.hopp

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.sovereigncast.hopp.platform.SecretStore
import space.sovereigncast.hopp.platform.TabOnly
import space.sovereigncast.hopp.platform.WebSecretStore

// The tab's own storage, gone with the tab: the mark that it keeps nothing, so a reload in it
// starts in memory again — not on whatever another listener left in the browser.
private fun marked(): Boolean = js("(() => { try { return sessionStorage.getItem('hopp-tab-only') === '1'; } catch (e) { return false; } })()")
private fun mark(): Unit = js("{ try { sessionStorage.setItem('hopp-tab-only', '1'); } catch (e) {} }")

/** Whether this tab chose to keep nothing — read before the database opens (Main.kt). */
internal fun tabOnlyMarked(): Boolean = marked()

/**
 * "This is not my device" in the browser (ADR 0022, 2): the database moves into the worker's
 * memory and its file in OPFS is deleted, the secrets stay in memory and their IndexedDB store is
 * deleted. The library and the sign-in carry on in this tab and are gone when it closes.
 * Whatever the browser held is taken along — the listener's own library from before signing in
 * (web/e2e: "the library carried over"), and on a browser others share, theirs: it is in this tab's
 * memory now, no longer stored (review 2026-10-31; by design, the page says it keeps nothing).
 */
class WebTabOnly(private val driver: SqlDriver, private val secrets: SecretStore, on: Boolean) : TabOnly {
    private val _active = MutableStateFlow(on)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    override suspend fun begin() {
        if (_active.value) return
        mark()
        keepDatabaseInMemory(driver)
        (secrets as? WebSecretStore)?.forget()
        _active.value = true
    }
}
