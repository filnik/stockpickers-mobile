package app.stockpickers.kmp.android

import android.app.Application
import app.stockpickers.kmp.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class StockpickersApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidLogger()
            androidContext(this@StockpickersApp)
        }
    }
}
