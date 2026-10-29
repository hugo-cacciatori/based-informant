package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.reminder.NextReminderCalculator
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.reminder.ReminderText
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.cancel
import io.github.hugo_cacciatori.basedinformant.ui.resources.ok
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_exact_allow
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_exact_off
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_next
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_notifications_off
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_switch
import io.github.hugo_cacciatori.basedinformant.ui.resources.reminder_time
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What the reminder needs from the platform: the permissions and the clock. Tests use the defaults. */
class ReminderPlatform(
    /** Asks for the notification permission when needed; the callback says whether notifications can be posted. */
    val askNotifications: (onResult: (Boolean) -> Unit) -> Unit = { it(true) },
    val exactAlarmsAllowed: () -> Boolean = { true },
    val openExactAlarmSettings: () -> Unit = {},
    val nowMillis: () -> Long = { 0L },
    val zone: () -> TimeZone = { TimeZone.UTC },
)

/**
 * The reminder in Settings (01-product.md section 6, owner 2026-10-29): the switch, any weekdays, one time, the next
 * reminder, and the alarm permission. Turning it on needs notifications (section 7): refused, it stays off and says why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSection(reminder: Reminder, onChange: (Reminder) -> Unit, platform: ReminderPlatform, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var pickingTime by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.reminder_section), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.reminder_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.reminder_switch), Modifier.weight(1f))
            Switch(
                checked = reminder.on,
                onCheckedChange = { on ->
                    if (!on) {
                        onChange(reminder.copy(on = false))
                    } else {
                        platform.askNotifications { allowed ->
                            if (allowed) onChange(reminder.copy(on = true))
                            else scope.launch { snackbar.showSnackbar(getString(Res.string.reminder_notifications_off)) }
                        }
                    }
                },
                modifier = Modifier.testTag("reminder-switch"),
            )
        }
        if (!reminder.on) return@Column
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayOfWeek.entries.forEach { day ->
                val picked = day in reminder.days
                FilterChip(
                    selected = picked,
                    onClick = { onChange(reminder.copy(days = if (picked) reminder.days - day else reminder.days + day)) },
                    label = { Text(ReminderText.day(day)) },
                    modifier = Modifier.testTag("reminder-day-${day.name.lowercase()}"),
                )
            }
        }
        TextButton(onClick = { pickingTime = true }, modifier = Modifier.testTag("reminder-time")) {
            Text(stringResource(Res.string.reminder_time, reminder.time.toString()))
        }
        NextReminderCalculator.next(reminder, platform.nowMillis(), platform.zone())?.let { next ->
            Text(stringResource(Res.string.reminder_next, ReminderText.at(next, platform.zone())), Modifier.testTag("reminder-next"))
        }
        if (!platform.exactAlarmsAllowed()) {
            Text(stringResource(Res.string.reminder_exact_off), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = platform.openExactAlarmSettings) { Text(stringResource(Res.string.reminder_exact_allow)) }
        }
    }
    if (pickingTime) {
        val picker = rememberTimePickerState(reminder.time.hour, reminder.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(picker) },
            confirmButton = {
                TextButton(onClick = {
                    pickingTime = false
                    onChange(reminder.copy(time = LocalTime(picker.hour, picker.minute)))
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
