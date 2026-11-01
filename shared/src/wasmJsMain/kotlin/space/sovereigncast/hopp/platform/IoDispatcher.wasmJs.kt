package space.sovereigncast.hopp.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// One thread in the browser: Default is the event loop.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
