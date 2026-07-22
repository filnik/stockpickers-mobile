package app.stockpickers.kmp.data.local

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

actual class DatabaseBuilderFactory(private val context: Context) {
    actual fun create(): RoomDatabase.Builder<AppDatabase> {
        val dbFile = context.getDatabasePath(AppDatabase.FILE_NAME)
        return Room.databaseBuilder<AppDatabase>(
            context = context.applicationContext,
            name = dbFile.absolutePath,
        )
    }
}
