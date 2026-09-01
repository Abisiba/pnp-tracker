package dev.pnptracker.domain.tasks

/*
 * What a number of pieces looks like while somebody is still typing it.
 *
 * Every field in the application that asks for an amount — a shortage reported,
 * a shortage made good, how far a step has got — asks for the same thing, so it
 * reads what was typed the same way. Two of them once had a length limit and a
 * parser of their own, and the limits disagreed with each other and with what a
 * count can actually be.
 *
 * There is deliberately **no** length limit here. A count is an [Int] and the
 * largest one has ten digits, so a field that stopped at nine would refuse a
 * number a task is allowed to hold — and would refuse it by quietly dropping
 * what was typed rather than by saying so. What will not fit is kept exactly as
 * it was written and refused where the user can see it.
 */

/** The digits of [typed], in order, and nothing else. */
fun quantityDigitsOf(typed: String): String = typed.filter(Char::isDigit)

/**
 * [typed] as a number of pieces, or null when it is not one.
 *
 * Empty is not a count, a word is not a count, and neither is a run of digits
 * too long to fit in one — the parse is safe, so hundreds of digits come back as
 * null rather than as an exception. Zeros written in front change nothing:
 * `0015` is fifteen.
 *
 * Only digits count. A minus sign is not a number of pieces the user could have
 * meant, and reading one would turn a slip into a movement backwards.
 */
fun countedQuantityOf(typed: String): Int? = typed.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

/** True once [typed] holds something that is not a count this field could take. */
fun isUnusableQuantity(typed: String): Boolean = typed.isNotEmpty() && countedQuantityOf(typed) == null
