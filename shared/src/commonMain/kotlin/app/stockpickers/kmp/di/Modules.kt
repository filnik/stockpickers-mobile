package app.stockpickers.kmp.di

import app.stockpickers.kmp.data.local.AppDatabase
import app.stockpickers.kmp.data.local.DatabaseBuilderFactory
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.buildDatabase
import app.stockpickers.kmp.data.remote.SupabaseScannerApi
import app.stockpickers.kmp.data.repository.TickerRepositoryImpl
import app.stockpickers.kmp.domain.GetMomentumLeadersUseCase
import app.stockpickers.kmp.domain.TickerRepository
import app.stockpickers.kmp.presentation.MomentumLeadersViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Platform-specific bindings (HttpClient engine, DatabaseBuilderFactory). */
expect val platformModule: Module

val coreModule = module {
    single {
        Json {
            ignoreUnknownKeys = true // upstream adds fields without warning
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
    single {
        // Engine (OkHttp / Darwin) is bound by platformModule.
        HttpClient(get<HttpClientEngine>()) {
            install(ContentNegotiation) { json(get<Json>()) }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 60_000
            }
        }
    }
    single { SupabaseScannerApi(get()) }

    single<AppDatabase> { get<DatabaseBuilderFactory>().buildDatabase() }
    single<ScannerDao> { get<AppDatabase>().scannerDao() }

    single<TickerRepository> { TickerRepositoryImpl(api = get(), dao = get()) }
    single { GetMomentumLeadersUseCase(get()) }

    viewModel { MomentumLeadersViewModel(repository = get(), getMomentumLeaders = get()) }
}

fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}) = startKoin {
    appDeclaration()
    modules(platformModule, coreModule)
}
