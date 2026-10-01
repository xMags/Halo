package moe.ditto.halo.screens

/**
 * The All / Movies / Series control that Home and Library share.
 *
 * [type] is the value the derivations take, `null` meaning no filter — the same
 * shape the API's `type` parameter uses, so nothing has to translate it.
 */
internal enum class MediaTypeFilter(val label: String, val type: String?) {
    All("All", null),
    Movies("Movies", "movie"),
    Series("Series", "series"),
    ;

    companion object {
        val labels: List<String> = entries.map { it.label }

        /** Resolves a segmented-control selection back to a filter; an unknown label changes nothing. */
        fun byLabel(label: String): MediaTypeFilter? = entries.firstOrNull { it.label == label }
    }
}
