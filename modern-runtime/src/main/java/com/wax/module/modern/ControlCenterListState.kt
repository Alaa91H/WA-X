package com.wax.module.modern

/**
 * Presentation-only filtering. The state is read from the shared runtime/Manager bridge;
 * this function never mutates feature preferences or hooks.
 */
object ControlCenterListState {
    fun visible(
        entries: List<ControlEntry>,
        query: String,
        category: ControlCategory?,
        favoritesOnly: Boolean,
        favorites: Set<String>,
        labels: ControlCenterLabels? = null,
    ): List<ControlEntry> = entries.filter { entry ->
        (!favoritesOnly || entry.id in favorites) &&
            (favoritesOnly || category == null || entry.category == category) &&
            (query.isBlank() || matches(entry, query, labels))
    }

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .lowercase().trim()

    private fun matches(entry: ControlEntry, query: String, labels: ControlCenterLabels?): Boolean {
        val needle = normalize(query)
        return sequenceOf(entry.title, entry.description, entry.category.name, entry.id,
            labels?.title(entry.id, entry.title).orEmpty(),
            labels?.description(entry.id, entry.description).orEmpty())
            .any { normalize(it).contains(needle) }
    }
}
