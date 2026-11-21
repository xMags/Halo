package moe.ditto.halo.browse

/** [text] in Unicode Normalization Form D (canonical decomposition), from the platform's Unicode tables. */
internal expect fun canonicalDecomposition(text: String): String
