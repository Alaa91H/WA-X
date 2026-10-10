package com.wax.module.modern

/**
 * Localization for the embedded Control Center (#433).
 *
 * The shell is built programmatically inside WhatsApp, and the modern
 * runtime module carries no AndroidX or bundled resource table, so it cannot
 * resolve strings from a layout XML or from the module's own resources. It
 * therefore owns a small, explicit string table instead of hardcoding English
 * text into the views.
 *
 * Selection is by the target's own current locale language, with English as
 * the fallback, so the surface follows the device language the user already
 * chose for WhatsApp. Missing translations degrade to English rather than
 * showing an empty label.
 */
object ControlCenterStrings {
    const val LANGUAGE_ARABIC = "ar"

    data class Table(
        val title: String,
        val searchHint: String,
        val noResults: String,
        val restart: String,
        val openManager: String,
        val favoritesOnly: String,
        val favorites: String,
        val favoriteToggleOff: String,
        val favoriteToggleOn: String,
        val markFavorite: String,
        val disabled: String,
        val hideAfterClicks: String,
        val hideWhileHolding: String,
        val runDiagnostics: String,
        val allFeatures: String,
        val restartConfirmation: String,
    )

    private val english = Table(
        title = "WA X",
        searchHint = "Search WA X features",
        noResults = "No WA X feature matches your search",
        restart = "Restart WhatsApp",
        openManager = "WA X Manager",
        favoritesOnly = "Favourites only",
        favorites = "Favourites",
        favoriteToggleOff = "Add to favourites",
        favoriteToggleOn = "Remove from favourites",
        markFavorite = "Mark as favourite",
        disabled = "Disabled",
        hideAfterClicks = "Hide after click count",
        hideWhileHolding = "Hide while holding the title",
        runDiagnostics = "Run diagnostics",
        allFeatures = "All",
        restartConfirmation = "Restart WhatsApp to apply pending changes?",
    )

    private val arabic = Table(
        title = "WA X",
        searchHint = "ابحث في مزايا WA X",
        noResults = "لا توجد ميزة WA X مطابقة للبحث",
        restart = "إعادة تشغيل واتساب",
        openManager = "مدير WA X",
        favoritesOnly = "المفضلة فقط",
        favorites = "المفضلة",
        favoriteToggleOff = "إضافة إلى المفضلة",
        favoriteToggleOn = "إزالة من المفضلة",
        markFavorite = "تعيين كمفضلة",
        disabled = "معطّل",
        hideAfterClicks = "إخفاء بعد عدد الضغطات",
        hideWhileHolding = "إخفاء أثناء الضغط على العنوان",
        runDiagnostics = "تشخيص ذاتي",
        allFeatures = "الكل",
        restartConfirmation = "هل تريد إعادة تشغيل واتساب لتطبيق التغييرات؟",
    )

    /** Unknown languages fall back to English instead of rendering blanks. */
    fun forLanguage(language: String?): Table =
        if (language != null && language.lowercase().startsWith(LANGUAGE_ARABIC)) arabic else english
}