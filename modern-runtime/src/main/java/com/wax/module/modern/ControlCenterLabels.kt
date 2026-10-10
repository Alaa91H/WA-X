package com.wax.module.modern

/**
 * Localized presentation strings for controls rendered in a foreign process.
 * Keep feature IDs stable; translated labels must never be used as persistence keys.
 */
class ControlCenterLabels private constructor(private val language: String) {
    private val resourceLabels = ControlCenterLocaleCatalog.values(language)

    fun title(id: String, fallback: String): String =
        if (language == "ar") arabicTitles[id] ?: resourceLabels[id] ?: fallback else resourceLabels[id] ?: fallback

    fun description(id: String, fallback: String): String =
        if (language == "ar") arabicDescriptions[id] ?: fallback else fallback

    fun category(category: ControlCategory): String =
        if (language == "ar") arabicCategories[category] ?: ControlStatusText.categoryTitle(category)
        else resourceLabels["category." + category.name.lowercase()] ?: ControlStatusText.categoryTitle(category)

    fun status(effective: ControlEffective): String =
        if (language == "ar") arabicStatus[effective] ?: ControlStatusText.status(effective)
        else ControlStatusText.status(effective)

    companion object {
        fun forLanguage(value: String?): ControlCenterLabels =
            ControlCenterLabels(
                when (value?.lowercase()?.substringBefore('-')?.substringBefore('_')) {
                    "iw", "he" -> "he"
                    "in", "id" -> "id"
                    null -> "en"
                    else -> value.lowercase().substringBefore('-').substringBefore('_')
                },
            )

        private val arabicTitles = mapOf(
            "custom_time" to "تخصيص الوقت",
            "share_limit" to "حد المشاركة",
            "freeze_last_seen" to "تجميد آخر ظهور",
            "view_once" to "الاحتفاظ بعرض الوسائط لمرة واحدة",
            "status_reply_seen_receipt" to "مشاهدة الحالة بعد الرد",
            "status_seen_hidden" to "إخفاء مشاهدة الحالة",
            "anti_revoke" to "منع حذف الرسائل",
            "receipt_privacy_read" to "إخفاء إشعار القراءة",
            "receipt_privacy_after_reply" to "إظهار القراءة بعد الرد",
            "receipt_privacy_delivery" to "إخفاء إشعار التسليم",
            "hide_chat" to "إخفاء المحادثات المؤرشفة",
            "typing_privacy" to "إخفاء الكتابة",
            "tasker" to "أتمتة Tasker",
            "dnd_mode" to "وضع عدم الإزعاج",
            "diagnostics" to "تشغيل التشخيص",
        )
        private val arabicDescriptions = mapOf(
            "custom_time" to "تخصيص عرض التوقيت داخل المحادثات",
            "share_limit" to "تحديد عدد المحادثات المشاركة",
            "freeze_last_seen" to "الاحتفاظ بقيمة آخر ظهور",
            "view_once" to "إبقاء الوسائط المعروضة مرة واحدة مفتوحة",
            "status_reply_seen_receipt" to "تسجيل مشاهدة الحالة بعد الرد عليها",
            "status_seen_hidden" to "عدم تسجيل مشاهدة الحالة عند فتحها",
            "anti_revoke" to "الاحتفاظ بالرسائل المستلمة بعد حذفها من المرسل",
            "receipt_privacy_read" to "عدم إرسال إشعار قراءة الرسائل",
            "receipt_privacy_after_reply" to "إرسال إشعار القراءة بعد الرد على المحادثة",
            "receipt_privacy_delivery" to "غير مدعوم حاليًا؛ التسليم تحدده خوادم واتساب",
            "hide_chat" to "إخفاء المحادثات المؤرشفة من القائمة",
            "typing_privacy" to "إخفاء مؤشر الكتابة عن جهات الاتصال",
            "tasker" to "تمرير الرسائل المستلمة إلى Tasker",
            "dnd_mode" to "إخفاء حالة الكتابة والاتصال",
            "diagnostics" to "فتح أدوات فحص الوحدة والتوافق",
        )
        private val arabicCategories = mapOf(
            ControlCategory.PRIVACY to "الخصوصية",
            ControlCategory.CHATS to "المحادثات",
            ControlCategory.MEDIA to "الوسائط",
            ControlCategory.APPEARANCE to "المظهر",
            ControlCategory.NOTIFICATIONS to "الإشعارات",
            ControlCategory.TOOLS to "الأدوات",
            ControlCategory.ADVANCED to "متقدم",
            ControlCategory.PENDING to "قيد التطوير",
        )
        private val arabicStatus = mapOf(
            ControlEffective.NOT_OBSERVED to "لم يتم التحقق من التشغيل بعد",
            ControlEffective.WORKING to "توجد أدلة تشغيل",
            ControlEffective.INSTALLED to "تم تثبيت الـHook",
            ControlEffective.DISABLED to "معطّل",
            ControlEffective.RESOLVER_FAILED to "تعذر تحديد نقطة الربط",
            ControlEffective.UNSAFE_SIGNATURE to "إصدار غير مدعوم",
            ControlEffective.UNSUPPORTED to "غير مدعوم في هذا الإصدار",
            ControlEffective.PENDING_MIGRATION to "بانتظار الترحيل",
            ControlEffective.ERROR to "خطأ في التشغيل",
            ControlEffective.RESTART_REQUIRED to "تلزم إعادة تشغيل واتساب",
            ControlEffective.PARTIAL to "الدعم جزئي",
        )
    }
}
