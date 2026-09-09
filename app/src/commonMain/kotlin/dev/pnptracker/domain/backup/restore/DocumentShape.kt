package dev.pnptracker.domain.backup.restore

import dev.pnptracker.domain.backup.BackupData
import dev.pnptracker.domain.backup.BackupEnvelopeV1
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

private val envelopeShape: SerialDescriptor = serializer<BackupEnvelopeV1>().descriptor
private val dataShape: SerialDescriptor = serializer<BackupData>().descriptor

/**
 * Holds a parsed document against the shape the format declares, field by field.
 *
 * This runs before anything is turned into a record, and it answers in the
 * format's own terms: this field is missing, this object has a field the format
 * does not know, this value is a string where a number belongs. A decoder would
 * find the same faults, but it would report them as its own exceptions — whose
 * messages carry the offending text, which is the user's data and must not
 * travel (PLAN 14.4.5). Asking the question here means the answer can be a name
 * and a place and nothing else.
 *
 * The shape is not written out again here. It is read from the serializers of
 * [BackupEnvelopeV1] and [BackupData], so a field added to the format is checked
 * from the moment it exists and there is no second list to keep in step.
 *
 * Numbers are checked for the range they will be read into, not only for being
 * numbers: a task count of `3000000000` is a number and is not an `Int`, and a
 * quantity of `1.0` is a number and is not a whole one. Both are refused here
 * rather than allowed to overflow or round somewhere later.
 *
 * The name of an unknown field is never carried out of this. It came from the
 * file and belongs to whoever wrote it.
 */
internal fun checkDocumentShape(root: JsonElement): BackupRejection? {
    val document = root as? JsonObject ?: return BackupRejection(BackupProblem.WRONG_TYPE, BackupPlace.File)
    checkFieldNames(document, envelopeShape, BackupPlace.Envelope)?.let { return it }

    for (index in 0 until envelopeShape.elementsCount) {
        val name = envelopeShape.getElementName(index)
        val shape = envelopeShape.getElementDescriptor(index)
        val value = document.getValue(name)
        if (shape.kind == StructureKind.CLASS) {
            val data = value as? JsonObject ?: return wrongType(BackupPlace.Envelope, name)
            checkData(data)?.let { return it }
        } else {
            checkValue(value, shape, BackupPlace.Envelope, name)?.let { return it }
        }
    }
    return null
}

/** The fifteen arrays, and every row of each. */
private fun checkData(data: JsonObject): BackupRejection? {
    checkFieldNames(data, dataShape, DATA)?.let { return it }

    for (index in 0 until dataShape.elementsCount) {
        val arrayName = dataShape.getElementName(index)
        val place = BackupPlace(arrayName)
        val rows = data.getValue(arrayName) as? JsonArray ?: return wrongType(DATA, arrayName)
        val rowShape = dataShape.getElementDescriptor(index).getElementDescriptor(0)
        for (row in rows) {
            val record = row as? JsonObject ?: return wrongType(place)
            checkFieldNames(record, rowShape, place)?.let { return it }
            for (field in 0 until rowShape.elementsCount) {
                val fieldName = rowShape.getElementName(field)
                val fieldShape = rowShape.getElementDescriptor(field)
                checkValue(record.getValue(fieldName), fieldShape, place, fieldName)?.let { return it }
            }
        }
    }
    return null
}

/**
 * Every field the shape declares is there, and nothing else is.
 *
 * Both halves matter and PLAN 14.4.1 says so: a missing field cannot be guessed
 * at, and an unknown one is data this version would drop on the floor and lose
 * at the next backup. The way to change the format is to change its version.
 */
private fun checkFieldNames(
    written: JsonObject,
    shape: SerialDescriptor,
    place: BackupPlace,
): BackupRejection? {
    val expected = shape.elementNames.toSet()
    expected.forEach { name ->
        if (name !in written) return BackupRejection(BackupProblem.MISSING_FIELD, place.copy(field = name))
    }
    written.keys.forEach { name ->
        if (name !in expected) return BackupRejection(BackupProblem.UNKNOWN_FIELD, place)
    }
    return null
}

/** One value, against the kind the record declares for it. */
private fun checkValue(
    value: JsonElement,
    shape: SerialDescriptor,
    place: BackupPlace,
    field: String,
): BackupRejection? {
    if (value is JsonNull) {
        // Present and null is a value; present and null where the format allows
        // no null is a document saying something it may not say.
        return if (shape.isNullable) null else wrongType(place, field)
    }
    val primitive = value as? JsonPrimitive ?: return wrongType(place, field)
    val ok =
        when (shape.kind) {
            PrimitiveKind.STRING -> primitive.isString
            PrimitiveKind.BOOLEAN -> !primitive.isString && (primitive.content == "true" || primitive.content == "false")
            PrimitiveKind.INT -> !primitive.isString && primitive.content.toIntOrNull() != null
            PrimitiveKind.LONG -> !primitive.isString && primitive.content.toLongOrNull() != null
            else -> false
        }
    return if (ok) null else wrongType(place, field)
}

private val DATA = BackupPlace("data")

private fun wrongType(
    place: BackupPlace,
    field: String? = null,
) = BackupRejection(BackupProblem.WRONG_TYPE, if (field == null) place else place.copy(field = field))
