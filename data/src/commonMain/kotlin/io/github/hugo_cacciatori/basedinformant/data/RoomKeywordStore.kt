package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.withWriteTransaction
import io.github.hugo_cacciatori.basedinformant.core.limits.InputChecks
import io.github.hugo_cacciatori.basedinformant.core.limits.LimitViolation
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordStore

/**
 * The keyword list in Room. A save replaces the list in one transaction; an entry kept from before keeps its
 * creation time. The data layer enforces the input limits too (testing.md): a list over them is never stored.
 */
class RoomKeywordStore(
    private val db: AppDatabase,
    private val nowMillis: () -> Long,
) : KeywordStore {
    override suspend fun load(): List<String> = db.keywords().all().map { it.text }

    override suspend fun save(keywords: List<String>) {
        val violations = InputChecks.keywords(keywords).filterNot { it is LimitViolation.TooFewKeywords }
        require(violations.isEmpty()) { "keywords over the limits: $violations" }
        db.withWriteTransaction {
            val created = db.keywords().all().associate { it.text to it.createdAtMillis }
            val now = nowMillis()
            db.keywords().deleteAll()
            db.keywords().insertAll(
                keywords.mapIndexed { i, raw ->
                    val text = raw.trim()
                    KeywordEntity(text = text, position = i, createdAtMillis = created[text] ?: now)
                },
            )
        }
    }
}
