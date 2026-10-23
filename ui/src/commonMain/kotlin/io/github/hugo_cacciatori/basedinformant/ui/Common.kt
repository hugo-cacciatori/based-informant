package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hugo_cacciatori.basedinformant.core.limits.FieldCounter
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.Refusal
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.field_counter
import io.github.hugo_cacciatori.basedinformant.ui.resources.refusal_blank
import io.github.hugo_cacciatori.basedinformant.ui.resources.refusal_duplicate
import io.github.hugo_cacciatori.basedinformant.ui.resources.refusal_full_keywords
import io.github.hugo_cacciatori.basedinformant.ui.resources.refusal_full_tags
import io.github.hugo_cacciatori.basedinformant.ui.resources.refusal_too_long_keyword
import io.github.hugo_cacciatori.basedinformant.ui.resources.refusal_too_long_tag
import org.jetbrains.compose.resources.stringResource

enum class ListKind { KEYWORDS, TAGS }

/** Why an edit of the keyword or tag list was refused, in words. */
@Composable
fun refusalText(refusal: Refusal, kind: ListKind): String = when (refusal) {
    Refusal.BLANK -> stringResource(Res.string.refusal_blank)
    Refusal.DUPLICATE -> stringResource(Res.string.refusal_duplicate)
    Refusal.TOO_LONG -> when (kind) {
        ListKind.KEYWORDS -> stringResource(Res.string.refusal_too_long_keyword, InputLimits.KEYWORD_MAX_CHARS)
        ListKind.TAGS -> stringResource(Res.string.refusal_too_long_tag, InputLimits.TAG_MAX_CHARS)
    }
    Refusal.FULL -> when (kind) {
        ListKind.KEYWORDS -> stringResource(Res.string.refusal_full_keywords, InputLimits.KEYWORDS_MAX)
        ListKind.TAGS -> stringResource(Res.string.refusal_full_tags, InputLimits.TAGS_MAX)
    }
}

/** Under a text field: the message on the left (a refusal or a limit), the "count / max" counter on the right. */
@Composable
fun FieldSupport(counter: FieldCounter, message: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(message.orEmpty(), Modifier.weight(1f))
        Text(stringResource(Res.string.field_counter, counter.count, counter.max))
    }
}

/** 120174 → "120,174": the UI is in English (product.md section 11), and common code has no locale formatter. */
fun groupThousands(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}
