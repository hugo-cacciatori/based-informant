package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/** The database in [folder]: the Windows app passes its folder under `%LOCALAPPDATA%`, tests a temporary one. */
fun databaseBuilder(folder: File): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(name = folder.resolve(AppDatabase.FILE_NAME).absolutePath)

/** The raw responses in [folder]`/raw`. */
fun rawFiles(folder: File): RawFiles = RawFiles(FileSystem.SYSTEM, folder.resolve(RawFiles.DIR_NAME).toOkioPath())
