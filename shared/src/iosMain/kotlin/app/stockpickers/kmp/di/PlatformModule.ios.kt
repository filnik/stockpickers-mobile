package app.stockpickers.kmp.di

import app.stockpickers.kmp.data.local.DatabaseBuilderFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<HttpClientEngine> { Darwin.create() }
    single { DatabaseBuilderFactory() }
}
