package dev.pnptracker.domain.colors

import dev.pnptracker.domain.rules.normalizeColorTerm

/**
 * Why one of the twelve could not be put back.
 *
 * PLAN 5.8 will not have a restore write over anything that is already there, so
 * a name that is taken is a refusal rather than something to work around. The
 * two ways a name can be taken are told apart because what the user has to do
 * about them is not the same: one belongs to a colour they can see and rename,
 * the other to a spelling of a colour they cannot.
 */
enum class BaseColorRestoreBlock {
    /** Another colour in the catalogue already carries this name. */
    NAME_TAKEN_BY_COLOR,

    /** Another colour is already known by this name, as one of its aliases. */
    NAME_TAKEN_BY_ALIAS,

    /** Something else put this colour back while the restore was being written. */
    APPEARED_MEANWHILE,
}

/** One base colour that stayed missing, and why. */
data class BlockedBaseColor(
    val canonicalName: String,
    val hex: String,
    val reason: BaseColorRestoreBlock,
)

/**
 * What restoring the base colours would do, worked out before anything is
 * written.
 *
 * Both halves are carried rather than only the first problem: PLAN 5.8 asks the
 * user to be told **which** colours could not be brought back and why, which a
 * single failure could not say.
 */
data class BaseColorRestorePlan(
    val missing: List<BaseColor>,
    val blocked: List<BlockedBaseColor>,
) {
    /** True when there is nothing at all to do; PLAN 5.8's no-op. */
    val isNothingMissing: Boolean get() = missing.isEmpty() && blocked.isEmpty()

    /**
     * True when a restore may go ahead.
     *
     * PLAN 5.8 is all or nothing, so one blocked colour stops the whole thing
     * rather than the eleven others going in without it.
     */
    val canRestore: Boolean get() = blocked.isEmpty() && missing.isNotEmpty()
}

/** How a restore ended. */
sealed interface BaseColorRestore {
    /** Every base colour was already there; nothing was written. */
    data object NothingMissing : BaseColorRestore

    /** The named colours were put back, and nothing else changed. */
    data class Restored(
        val canonicalNames: List<String>,
    ) : BaseColorRestore

    /** Nothing was written, because these could not be put back. */
    data class Blocked(
        val blocked: List<BlockedBaseColor>,
    ) : BaseColorRestore
}

/**
 * Thrown when a base colour turned up between the check and the write.
 *
 * Its own type rather than a storage failure: the row that appeared is a real
 * colour somebody else wrote, so overwriting it would be exactly what PLAN 5.8
 * forbids. The whole restore is undone and the user is told which colour it was.
 */
class BaseColorRestoreConflict(
    val canonicalName: String,
    cause: Throwable? = null,
) : Exception("The base colour '$canonicalName' was written by something else.", cause)

/**
 * What a restore would do, given the catalogue and every alias in it.
 *
 * Pure, and worked out from two lists rather than from the database, so the
 * screen showing the preview and the transaction doing the work reach the same
 * answer through the same code instead of two that could drift.
 *
 * Missing means one thing only: the fixed identity is not in [catalogue]. PLAN
 * 5.7 lets the user rename a base colour and change what it is, so a row that
 * has been edited beyond recognition is still that base colour and is left
 * exactly as the user made it.
 */
fun planBaseColorRestore(
    catalogue: List<ColorSummary>,
    aliasTerms: List<String>,
): BaseColorRestorePlan {
    val present = catalogue.mapTo(mutableSetOf()) { it.id }
    val missing = baseColors.filter { it.id !in present }
    if (missing.isEmpty()) return BaseColorRestorePlan(missing = emptyList(), blocked = emptyList())

    val takenNames = catalogue.associateBy { normalizeColorTerm(it.canonicalName) }
    val takenAliases = aliasTerms.mapTo(mutableSetOf()) { normalizeColorTerm(it) }
    val blocked =
        missing.mapNotNull { base ->
            val normalized = normalizeColorTerm(base.canonicalName)
            when {
                takenNames.containsKey(normalized) ->
                    BlockedBaseColor(base.canonicalName, base.hex, BaseColorRestoreBlock.NAME_TAKEN_BY_COLOR)

                normalized in takenAliases ->
                    BlockedBaseColor(base.canonicalName, base.hex, BaseColorRestoreBlock.NAME_TAKEN_BY_ALIAS)

                else -> null
            }
        }
    return BaseColorRestorePlan(missing = missing, blocked = blocked)
}
