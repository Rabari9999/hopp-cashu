package space.sovereigncast.hopp.value

/**
 * A wallet the browser itself offers the page — WebLN, from an extension such
 * as Alby (ADR 0022, 4, stage W3). Nothing that can spend is kept: the
 * extension holds the wallet and asks the listener as it sees fit, its own
 * budget deciding what it pays without asking; Hopp's daily budget applies on
 * top, per browser. [WalletRepository] keeps only that the listener chose it.
 */
interface BrowserWallet {
    /** Whether the browser has one now. */
    fun available(): Boolean

    /** Lets Hopp pay through it — the extension may ask the listener — and says what it can. */
    suspend fun enable(): ConnectedWallet

    /** The wallet to pay with, once [enable]d; it enables itself again after a reload. */
    fun client(): WalletClient
}
