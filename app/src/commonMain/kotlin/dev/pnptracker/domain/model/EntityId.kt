package dev.pnptracker.domain.model

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Identity of a persisted domain entity. Identifiers are produced by the
 * application, never by the database, so a record keeps the same identity across
 * exports, backups and any future synchronisation.
 *
 * Identifiers travel as [EntityId] inside the domain, never as [String]; the text
 * form exists only at the edges, such as a database column.
 */
@JvmInline
value class EntityId(
    val value: Uuid,
) {
    /** The canonical lower case, hyphenated text form, e.g. for a database column. */
    override fun toString(): String = value.toString()

    companion object {
        private const val CANONICAL_LENGTH = 36
        private val HYPHEN_POSITIONS = intArrayOf(8, 13, 18, 23)

        /**
         * Reads an identifier back from its canonical text form.
         *
         * Only the hyphenated form is accepted, so [toString] and [parse] are exact
         * inverses. `Uuid.parse` on its own would also accept 32 bare hex digits,
         * which would let a stored value differ from the one this type writes.
         *
         * @throws IllegalArgumentException if [text] is not a canonical UUID.
         */
        fun parse(text: String): EntityId {
            require(text.length == CANONICAL_LENGTH && HYPHEN_POSITIONS.all { text[it] == '-' }) {
                "Not a canonical uuid (expected xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx): '$text'"
            }
            return EntityId(Uuid.parse(text))
        }
    }
}

/**
 * Source of new identifiers. Production code takes one instead of generating
 * identifiers inline, so tests can hand out predictable values.
 */
fun interface IdGenerator {
    fun newId(): EntityId

    companion object {
        /** The production generator: random (version 4) UUIDs. */
        val Random: IdGenerator = IdGenerator { EntityId(Uuid.random()) }
    }
}
