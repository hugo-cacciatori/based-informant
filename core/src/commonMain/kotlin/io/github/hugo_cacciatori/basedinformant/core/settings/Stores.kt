package io.github.hugo_cacciatori.basedinformant.core.settings

/** The keyword list as stored (Room, `04-platforms.md` section 7): trimmed entries in the order they were added. */
interface KeywordStore {
    suspend fun load(): List<String>

    /** Replaces the whole list; it holds at most 25 short entries. */
    suspend fun save(keywords: List<String>)
}

/** The settings as stored (DataStore, `04-platforms.md` section 7). Never holds the key. */
interface SettingsStore {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}
