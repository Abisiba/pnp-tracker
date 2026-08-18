package dev.pnptracker.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EntityIdTest {
    private val canonicalForm = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    @Test
    fun `a generated id uses the canonical hyphenated form`() {
        repeat(100) {
            val text = IdGenerator.Random.newId().toString()
            assertTrue(canonicalForm.matches(text), "not a canonical uuid: $text")
        }
    }

    @Test
    fun `a generated id is lower case`() {
        repeat(100) {
            val text = IdGenerator.Random.newId().toString()
            assertEquals(text.lowercase(), text)
        }
    }

    @Test
    fun `a generated id is a version 4 uuid`() {
        repeat(100) {
            val text = IdGenerator.Random.newId().toString()
            assertEquals('4', text[14], "version nibble must be 4 in $text")
            assertTrue(text[19] in "89ab", "variant nibble must be one of 8, 9, a, b in $text")
        }
    }

    @Test
    fun `an id survives a round trip through its text form`() {
        val original = IdGenerator.Random.newId()

        val restored = EntityId.parse(original.toString())

        assertEquals(original, restored)
        assertEquals(original.toString(), restored.toString())
    }

    @Test
    fun `parsing is case insensitive but the text form stays lower case`() {
        val original = IdGenerator.Random.newId()

        val restored = EntityId.parse(original.toString().uppercase())

        assertEquals(original, restored)
        assertEquals(original.toString(), restored.toString())
    }

    @Test
    fun `text that is not a uuid is rejected`() {
        val invalidValues =
            listOf(
                "",
                "   ",
                "not-a-uuid",
                "123",
                "6b1f4a2e-8c7d-4e5f-a9b0-c1d2e3f4a5b",
                "6b1f4a2e-8c7d-4e5f-a9b0-c1d2e3f4a5b6-extra",
                "gggggggg-8c7d-4e5f-a9b0-c1d2e3f4a5b6",
            )

        invalidValues.forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("should have been rejected: '$invalid'") {
                EntityId.parse(invalid)
            }
        }
    }

    @Test
    fun `the hyphenless hex form is rejected even though the kotlin stdlib accepts it`() {
        val canonical = "6b1f4a2e-8c7d-4e5f-a9b0-c1d2e3f4a5b6"
        val hyphenless = canonical.replace("-", "")

        assertEquals(canonical, EntityId.parse(canonical).toString())
        val failure = assertFailsWith<IllegalArgumentException> { EntityId.parse(hyphenless) }

        assertTrue(failure.message.orEmpty().contains("canonical"))
    }

    @Test
    fun `ten thousand generated ids contain no duplicate`() {
        val count = 10_000

        val ids = List(count) { IdGenerator.Random.newId() }

        assertEquals(count, ids.toSet().size)
    }

    @Test
    fun `equality is decided by the uuid value`() {
        val text = IdGenerator.Random.newId().toString()

        val first = EntityId.parse(text)
        val second = EntityId.parse(text)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, IdGenerator.Random.newId())
    }

    @Test
    fun `an id is backed by the kotlin uuid type and not by the jvm one`() {
        val id = IdGenerator.Random.newId()

        assertEquals("kotlin.uuid.Uuid", id.value::class.qualifiedName)
    }

    @Test
    fun `an injected generator can hand out predictable ids`() {
        val fixed = EntityId.parse("6b1f4a2e-8c7d-4e5f-a9b0-c1d2e3f4a5b6")
        val generator = IdGenerator { fixed }

        assertEquals(fixed, generator.newId())
        assertEquals(fixed, generator.newId())
    }
}
