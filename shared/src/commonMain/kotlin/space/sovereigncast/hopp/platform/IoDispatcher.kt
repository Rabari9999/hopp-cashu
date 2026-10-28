package space.sovereigncast.hopp.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Where database and file work runs: `Dispatchers.IO` on Android, the JVM and
 *  iOS. The browser has no blocking IO and no such dispatcher (ADR 0022). */
internal expect val ioDispatcher: CoroutineDispatcher
