package io.github.hugo_cacciatori.basedinformant.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataStoreSettingsStoreTest {
    private val folder = Files.createTempDirectory("bi-settings").toFile()
    private val dataStore = settingsDataStore(folder.resolve(DataStoreSettingsStore.FILE_NAME).absolutePath)
    private val store = DataStoreSettingsStore(dataStore)

    @AfterTest
    fun clean() {
        folder.deleteRecursively()
    }

    @Test
    fun anEmptyFileReadsAsTheDefaults() = runBlocking {
        assertEquals(AppSettings(), store.load())
    }

    @Test
    fun savesAndLoadsEveryFieldWithTheTagOrder() = runBlocking {
        val settings = AppSettings(
            tags = listOf("zeta", "alpha"),
            attentionMode = AttentionMode.NICHE,
            persona = Persona.DOCTOR_WIFE,
            reminder = Reminder(on = true, days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY), time = LocalTime(19, 5)),
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun aProfileStoredByAnOlderVersionIsErasedOnSave() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("profile")] = "I follow energy news." }
        store.save(AppSettings())
        assertEquals(null, dataStore.data.first()[stringPreferencesKey("profile")])
    }

    @Test
    fun unknownStoredValuesReadAsDefaults() = runBlocking {
        dataStore.edit {
            it[stringPreferencesKey("persona")] = "RENAMED"
            it[stringPreferencesKey("attention_mode")] = "GONE"
            it[stringPreferencesKey("tags")] = "not json"
        }
        assertEquals(AppSettings(), store.load())
    }

    @Test
    fun overLimitSettingsAreNotStored() = runBlocking {
        assertFailsWith<IllegalArgumentException> { store.save(AppSettings(tags = List(16) { "t$it" })) }
        assertEquals(AppSettings(), store.load())
    }
}
