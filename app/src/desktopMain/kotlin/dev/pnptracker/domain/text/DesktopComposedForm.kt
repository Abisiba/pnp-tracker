package dev.pnptracker.domain.text

import java.text.Normalizer

/**
 * The JVM's own canonical composition, kept behind the shared declaration.
 *
 * `Normalizer.Form.NFC` is the runtime's implementation of Unicode's canonical
 * composition, so nothing here needs tables of its own and no dependency is
 * added to get them. NFC and not NFKC: the compatibility forms would rewrite
 * text the user really typed — turning `①` into `1` and a full width letter into
 * a narrow one — and searching is not a licence to decide what somebody meant.
 *
 * `isNormalized` first, because almost every string already is: the check is a
 * scan and the conversion allocates, and this runs over every name on screen.
 */
actual fun composedForm(text: String): String =
    if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)
