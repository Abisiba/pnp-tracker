package dev.pnptracker.domain.diagnostics

import androidx.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Gives an observed reading an answer for storage refusing it, and records that
 * refusal once (PLAN 14.7.6).
 *
 * Only a refusal by storage is answered. Everything else is thrown on exactly as
 * it arrived: a defect above or below is not a database that would not answer
 * and is never filed as one (PLAN 14.4.5), an [Error] is nobody's to swallow,
 * and the cancellation that ends every screen is not a failure and is neither
 * answered nor recorded. The narrowing is what this is for — a `catch` over
 * everything turns a null nobody expected into "could not be read" and the
 * defect is never seen again.
 *
 * The stream ends where the refusal is caught, which is what a refusal means:
 * a reading that did not happen has nothing more to send. Asking again is the
 * user's to do and collecting again is all it takes, so one refusal writes one
 * line however long the screen stays open.
 *
 * [refused] is what the screen says instead, and is called on the collecting
 * coroutine before the stream ends.
 */
fun <T> Flow<T>.answeringStorageRefusal(
    area: DiagnosticArea,
    diagnostics: Diagnostics,
    refused: () -> Unit,
): Flow<T> =
    catch { failure ->
        if (failure !is SQLiteException) throw failure
        diagnostics.recordSafely { storageReadFailed(area, failure) }
        refused()
    }
