package space.sovereigncast.hopp

/**
 * Payments that unlock an episode, publish it sooner, or pay for a suggestion,
 * and the app split. Off in the App Store binary (ADR 0009) — fixed at build
 * time, never a remote flag. Episode boosts and streaming are not these.
 */
internal fun storePaysForContent(): Boolean = !Distribution.appStore
