package io.github.hugo_cacciatori.basedinformant.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** The database in the app's private database folder. */
fun databaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<AppDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(AppDatabase.FILE_NAME).absolutePath,
    )
}

/** The settings file in the app's private files folder. */
fun settingsPath(context: Context): String =
    context.applicationContext.filesDir.resolve(DataStoreSettingsStore.FILE_NAME).absolutePath

/** The raw responses in the app's private files folder (platforms.md section 7). */
fun rawFiles(context: Context): RawFiles =
    RawFiles(FileSystem.SYSTEM, context.applicationContext.filesDir.resolve(RawFiles.DIR_NAME).toOkioPath())
