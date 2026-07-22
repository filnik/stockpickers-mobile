package app.stockpickers.kmp.di

import app.stockpickers.kmp.data.local.AppDatabase
import app.stockpickers.kmp.data.local.DatabaseBuilderFactory
import app.stockpickers.kmp.data.local.ScannerDao
import app.stockpickers.kmp.data.local.buildDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.KoinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.context.startKoin
import org.koin.core.module.Module as KoinModule

/**
 * Platform-specific bindings (HttpClient engine, DatabaseBuilderFactory).
 *
 * This one stays classic DSL on purpose while the rest of the graph is annotated.
 * Its bindings are not classes we own — `OkHttp.create()` / `Darwin.create()` are
 * factory calls, and the Android database builder needs `androidContext()` — so
 * there is nothing to put an annotation on. Mixing the two styles is supported;
 * [initKoin] simply passes both modules.
 */
expect val platformModule: KoinModule

/**
 * The shared graph.
 *
 * `@ComponentScan` picks up everything annotated under the package: the three API
 * clients, the repository, the nine use cases and both ViewModels. Only the
 * third-party types that cannot carry an annotation are declared by hand below.
 *
 * Scope rule for this project: **everything is `@Single` except ViewModels.** Koin
 * singles are lazy unless `createdAtStart`, and these are stateless collaborators
 * holding one reference each — a `@Factory` would allocate per injection for no
 * benefit. (Wishew's Koin guide lists "single for use cases" as a mistake; that is
 * advice for a 200-module app with heavyweight graphs, and it does not transfer.)
 */
@Module
@ComponentScan("app.stockpickers.kmp")
class AppModule {

    @Single
    fun json(): Json = Json {
        ignoreUnknownKeys = true // upstream adds fields without warning
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * One client for every remote source. The engine comes from [platformModule].
     *
     * Note this is shared with YahooChartApi, which is why the Supabase auth headers
     * are set per request and never in a `defaultRequest`: a client-wide header would
     * ship the anon key to a third party.
     */
    @Single
    fun httpClient(engine: HttpClientEngine, json: Json): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 60_000
        }
    }

    @Single
    fun appDatabase(factory: DatabaseBuilderFactory): AppDatabase = factory.buildDatabase()

    @Single
    fun scannerDao(database: AppDatabase): ScannerDao = database.scannerDao()
}

fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}) = startKoin {
    appDeclaration()
    modules(platformModule, AppModule().module())
}
