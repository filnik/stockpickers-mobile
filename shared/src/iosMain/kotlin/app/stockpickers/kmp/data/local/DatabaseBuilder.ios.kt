package app.stockpickers.kmp.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual class DatabaseBuilderFactory {
    @OptIn(ExperimentalForeignApi::class)
    actual fun create(): RoomDatabase.Builder<AppDatabase> {
        val documentsUrl: NSURL = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        ) ?: error("Unable to resolve the iOS Documents directory")
        val dbPath = requireNotNull(documentsUrl.path) + "/" + AppDatabase.FILE_NAME
        return Room.databaseBuilder<AppDatabase>(name = dbPath)
    }
}
