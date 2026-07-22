package app.stockpickers.kmp.di

import app.stockpickers.kmp.data.local.DatabaseBuilderFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.module.Module as KoinModule

/** iOS's half of the platform seam. See the Android actual for why it is annotated. */
@Module
class IosPlatformModule {

    @Single
    fun httpClientEngine(): HttpClientEngine = Darwin.create()

    /** No Context to find here — the database path comes from NSDocumentDirectory. */
    @Single
    fun databaseBuilderFactory(): DatabaseBuilderFactory = DatabaseBuilderFactory()
}

actual val platformModule: KoinModule = IosPlatformModule().module()
