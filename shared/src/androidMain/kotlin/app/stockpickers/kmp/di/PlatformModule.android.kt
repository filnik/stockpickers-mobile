package app.stockpickers.kmp.di

import app.stockpickers.kmp.data.local.DatabaseBuilderFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.scope.Scope
import org.koin.core.module.Module as KoinModule

/**
 * Android's half of the platform seam.
 *
 * Annotated rather than classic DSL so the compile-time graph check can see these
 * two bindings: they are the only things `commonMain` asks for that it cannot
 * provide itself, and as a runtime-only DSL module they were invisible to it.
 */
@Module
class AndroidPlatformModule {

    @Single
    fun httpClientEngine(): HttpClientEngine = OkHttp.create()

    /**
     * Takes the [Scope] rather than a `Context` parameter: the Context is registered
     * by `androidContext(...)` at startup, not by a binding the checker can see, and
     * declaring it as a constructor/function dependency is the shape that trips
     * koin-annotations#37.
     */
    @Single
    fun databaseBuilderFactory(scope: Scope): DatabaseBuilderFactory = DatabaseBuilderFactory(scope.androidContext())
}

actual val platformModule: KoinModule = AndroidPlatformModule().module()
