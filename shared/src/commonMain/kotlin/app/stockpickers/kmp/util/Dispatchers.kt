package app.stockpickers.kmp.util

import kotlinx.coroutines.CoroutineDispatcher

/**
 * `Dispatchers.IO` is not part of the common coroutines API: it is public on
 * JVM/Android but not resolvable from commonMain for Kotlin/Native targets.
 * Each platform therefore supplies the dispatcher used for blocking I/O.
 */
internal expect val ioDispatcher: CoroutineDispatcher
