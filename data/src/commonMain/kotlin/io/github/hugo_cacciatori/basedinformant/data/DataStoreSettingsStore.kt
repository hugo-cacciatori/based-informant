package io.github.hugo_cacciatori.basedinformant.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * The settings in DataStore (04-platforms.md section 7). A missing or unreadable value reads as its default, so an
 * update that renames an option never breaks Settings. Never holds the key. Over-limit settings are not stored.
 */
class DataStoreSettingsStore(private val store: DataStore<Preferences>) : SettingsStore {
    override suspend fun load(): AppSettings {
        val prefs = store.data.first()
        val defaults = AppSettings()
        return AppSettings(
            tags = prefs[TAGS]?.let(::decodeTags) ?: defaults.tags,
            attentionMode = prefs[ATTENTION]?.let { name -> AttentionMode.entries.find { it.name == name } }
                ?: defaults.attentionMode,
            persona = prefs[PERSONA]?.let { name -> Persona.entries.find { it.name == name } } ?: defaults.persona,
            model = prefs[MODEL] ?: defaults.model,
            reminder = Reminder(
                on = prefs[REMINDER_ON] ?: defaults.reminder.on,
                days = prefs[REMINDER_DAYS]?.let(::decodeDays) ?: defaults.reminder.days,
                time = prefs[REMINDER_TIME]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: defaults.reminder.time,
            ),
        )
    }

    override suspend fun save(settings: AppSettings) {
        require(settings.violations().isEmpty()) { "settings over the limits: ${settings.violations()}" }
        store.edit { prefs ->
            // The profile description was removed (owner, 2026-10-20): erase what an older version stored.
            prefs.remove(LEGACY_PROFILE)
            // A JSON list keeps the tags' order, which a string set would lose.
            prefs[TAGS] = json.encodeToString(tagsSerializer, settings.tags)
            prefs[ATTENTION] = settings.attentionMode.name
            prefs[PERSONA] = settings.persona.name
            prefs[MODEL] = settings.model
            prefs[REMINDER_ON] = settings.reminder.on
            prefs[REMINDER_DAYS] = settings.reminder.days.sorted().joinToString(",") { it.name }
            prefs[REMINDER_TIME] = settings.reminder.time.toString()
        }
    }

    /** "MONDAY,FRIDAY"; unknown names are skipped, and nothing readable reads as the default. */
    private fun decodeDays(text: String): Set<DayOfWeek>? =
        text.split(',').mapNotNull { name -> DayOfWeek.entries.find { it.name == name.trim() } }.toSet().ifEmpty { null }

    private fun decodeTags(text: String): List<String>? =
        runCatching { json.decodeFromString(tagsSerializer, text) }.getOrNull()

    companion object {
        const val FILE_NAME = "settings.preferences_pb"

        private val json = Json
        private val tagsSerializer = ListSerializer(String.serializer())
        private val LEGACY_PROFILE = stringPreferencesKey("profile")
        private val TAGS = stringPreferencesKey("tags")
        private val ATTENTION = stringPreferencesKey("attention_mode")
        private val PERSONA = stringPreferencesKey("persona")
        private val MODEL = stringPreferencesKey("model")
        private val REMINDER_ON = booleanPreferencesKey("reminder_on")
        private val REMINDER_DAYS = stringPreferencesKey("reminder_days")
        private val REMINDER_TIME = stringPreferencesKey("reminder_time")
    }
}

/** The settings file at [path] in the app's private storage, made by each platform host. */
fun settingsDataStore(path: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
