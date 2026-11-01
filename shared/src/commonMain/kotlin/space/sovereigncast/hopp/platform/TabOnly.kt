package space.sovereigncast.hopp.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * "This is not my device", a choice at sign-in in the browser (ADR 0022, 2):
 * nothing is stored, and everything is gone when the tab closes.
 */
interface TabOnly {
    /** Whether this tab keeps nothing; once on, it stays on until the tab closes. */
    val active: StateFlow<Boolean>
    /** Moves the library and the secrets into memory and deletes what was stored of them. */
    suspend fun begin()
}
