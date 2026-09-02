package dev.pnptracker.domain.tasks

/**
 * The four marks a task carries about the work rather than about its progress.
 *
 * PLAN 10 gives `Eksik` and `Ödünç Parçalar` a column each in the source file,
 * and says in the same breath that neither is a pool: a marked task still has to
 * be placed in one. PLAN 11.7 adds `Bilgi eksik` for the sentences in the file
 * that never finished — an amount nobody wrote down, a colour nobody chose — and
 * PLAN 10 adds `Sınıflandırma gerekli` for work whose pool was the user's own
 * decision rather than its column's.
 *
 * Carried as one shape so that an edit which does not mean to touch them can say
 * so by leaving them out, rather than by repeating four values it never read.
 */
data class TaskFlags(
    val isMissing: Boolean = false,
    val isBorrowed: Boolean = false,
    val needsInfo: Boolean = false,
    val needsClassification: Boolean = false,
) {
    /** True for the one pair PLAN 10 makes impossible: a cell is in one column. */
    val conflict: Boolean get() = isMissing && isBorrowed
}
