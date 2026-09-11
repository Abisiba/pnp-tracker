package dev.pnptracker.ui.feature.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pnptracker.domain.backup.automatic.StartupProblem
import dev.pnptracker.ui.Strings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What a person sees instead of the application when it would not open their
 * database.
 *
 * PLAN 14.4.10 puts this window inside job 4 rather than leaving it to the
 * recovery work later on, and the reason is that the gate in front of a
 * migration is worth nothing if the refusal it produces is a stack trace. So
 * this says three things and no more: that the application did not open, why in
 * one plain sentence, and — for every case but one — that nothing of theirs was
 * changed.
 *
 * The exception is a real migration that failed. There the sentence says where
 * the backup is, in words, because that is the one case where something was
 * attempted on their own database and the copy taken beforehand is the thing
 * that matters. No path is shown, here or anywhere else (PLAN 14.4.5).
 */
@Composable
fun StartupErrorScreen(
    problem: StartupProblem,
    onClose: () -> Unit,
) {
    val title = stringResource(Strings.Startup.title)
    val body = stringResource(messageOf(problem))
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(32.dp).widthIn(max = 640.dp).semantics(mergeDescendants = false) {},
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
            // Said separately from the reason, and not said at all where it
            // would be untrue: a failed migration is the one case where the
            // database really was opened.
            if (problem != StartupProblem.MIGRATION_FAILED) {
                Text(text = stringResource(Strings.Startup.dataSafe), style = MaterialTheme.typography.bodyMedium)
            }
            val close = stringResource(Strings.Startup.close)
            Button(onClick = onClose, modifier = Modifier.semantics { contentDescription = close }) {
                Text(close)
            }
        }
    }
}

/**
 * One sentence per reason, with no `else`.
 *
 * Exhaustive on purpose: a new reason must be given its own words rather than
 * quietly inheriting somebody else's, and the compiler is what enforces that.
 */
fun messageOf(problem: StartupProblem): StringResource =
    when (problem) {
        StartupProblem.ANOTHER_COPY_IS_RUNNING -> Strings.Startup.anotherCopy
        StartupProblem.DATABASE_NOT_READABLE -> Strings.Startup.notReadable
        StartupProblem.SCHEMA_TOO_NEW -> Strings.Startup.tooNew
        StartupProblem.SNAPSHOT_NOT_CLONED -> Strings.Startup.notCloned
        StartupProblem.SNAPSHOT_NOT_MIGRATED -> Strings.Startup.notMigrated
        StartupProblem.SNAPSHOT_NOT_WRITTEN -> Strings.Startup.notWritten
        StartupProblem.SNAPSHOT_NOT_VERIFIED -> Strings.Startup.notVerified
        StartupProblem.MIGRATION_FAILED -> Strings.Startup.migrationFailed
    }
