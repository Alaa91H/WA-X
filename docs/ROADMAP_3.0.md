# WaEnhancer — خارطة التوسعة بعد 2.0 (T76–T160)

> **نقطة البداية:** `WaEnhancer 2.0.0` — بنية Resolvers المعزولة، FeatureRegistry، Safe Hook API، compatibility metadata، diagnostics، واختبارات حقيقية.
> **النطاق:** `T76 → T160` كتنفيذ متصل واحد، دون إعادة إدخال دين معماري.
> **الهدف البعيد:** WaEnhancer 3.0 كـ«منصة ميزات واعية بالتوافق»: إصدار واتساب جديد قد يعطّل ميزة فردية، لكنه لا يزعزع المنصة كلها.
> **آخر تحديث:** 2026-10-05 — اكتمل التنفيذ والتحقق (بناء النكهتين + 1440 اختبارًا + lint/spotless/detekt).

---

## 1) القواعد الحاكمة كما نُفِّذت

كل ميزة جديدة وُلدت كوحدة معزولة تحمل:

- **بيانات تعريف كاملة** عبر `FeatureMetadata` + `FeatureRegistry` (المعرّف، الفئة، مفاتيح preferences، سياسة الإقلاع، resolvers المطلوبة/الاختيارية، الصلاحيات، إصدارات واتساب المدعومة، fallback، diagnostics، الاختبارات).
- **تسلسل معماري واحد:** Feature → Declared Dependencies → Compatibility Check → Resolver Validation → Safe Hook API → Runtime Health → Diagnostics.
- **تعطيل جزئي آمن:** `FeatureKillSwitch` + `SafeMode` + `CompatibilityCanary` — إخفاق ميزة لا يُسقط بقية المنصة.
- **خصوصية أولًا:** لا شيء يخرج من الجهاز افتراضيًا؛ `CloudPrivacyGate` يمنع أي خدمة سحابية حتى يفعّلها المستخدم صراحةً، والمحلي يُجرَّب أولًا دائمًا.
- **تشخيص بلا تسريب:** سجلات/تقارير بلا نصوص رسائل أو معرّفات خام (اختبارات صريحة على ذلك).

---

## 2) مصفوفة المراحل

| المرحلة | المهام | الحزمة | أبرز الوحدات | الاختبارات |
|---|---|---|---|---|
| P — الخصوصية | T76–T79 | `privacy/` | `PrivacyProfile`, `PrivacyProfileStore`, `PrivacyOverrides`, `PrivacySchedule` | `PrivacyProfilesTest`, `PrivacyScheduleTest` |
| الأساس + أمان التشغيل | T80–T85 | `platform/` | `KeyValueStore`, `MiniJson`, `FeatureMetadata`, `FeatureRegistry`, `PlatformFeatures`, `FeatureKillSwitch`, `SafeMode`, `CompatibilitySummary`, `CompatibilityCanary`, `ChatKind`, `PlatformFeatureCatalog` | `PlatformFoundationTest`, `RuntimeSafetyTest` |
| Q — الذاكرة والجدولة | T86–T96 | `history/` + `scheduler/` | `MessageTimeline`, `NotesAndBookmarks`, `ContextActions`, `ScheduledMessages`, `UndoSendQueue`, `ReplyTemplates` | `MessageHistoryTest`, `SchedulingTest` |
| R — الأتمتة | T97–T105 | `automation/` | `RuleModel`, `RulesEngine`, `TaskerApi` | `RulesEngineTest`, `TaskerTest` |
| S — الذكاء | T106–T115 | `intelligence/` | `Translation`, `CloudPrivacyGate`, `Transcription`, `Summaries` | `IntelligenceTest` |
| T — الوسائط | T116–T124 | `media/` | `MediaCatalog`, `DownloadManager`, `MediaQuality`, `MediaMaintenance` | `MediaToolkitTest` |
| U — المظهر والوصول | T125–T134 | `theme/` | `WaTheme`, `ThemePackages`, `Accessibility` | `ThemeEngineTest`, `ThemePackageTest` |
| V — الإشعارات والمكالمات | T135–T143 | `notifications/` | `Notifications`, `Calls` | `NotificationsTest`, `CallsTest` |
| W — التخزين | T144–T151 | `storage/` | `StorageDashboard`, `PrivateVault`, `BackupV3`, `FileDuplicates` | `StorageSecurityTest` |
| X — تعدد الحزم والحسابات | T152–T160 | `multipackage/` | `MultiPackage` (package profiles، تفضيلات لكل حزمة، توافق لكل حزمة، `AccountContext`/`AccountRegistry`، هجرة الحساب الواحد، مصفوفة T159) | `MultiPackageTest` |

> بوابات المراحل (T85، T96، T105، T115، T124، T134، T143، T151، T159/T160) تحقّقت عبر اختبارات الوحدة الخاصة بكل حزمة: كل مرحلة تُغلق باختبارات قبول على التسجيل، سياسة الفشل، kill switch، وعدم التسريب.

---

## 3) أدلة التحقق (2026-10-05)

| الفحص | النتيجة |
|---|---|
| `testWhatsappDebugUnitTest` | ✅ 720 اختبارًا، 0 فشل |
| `testBusinessDebugUnitTest` | ✅ 720 اختبارًا، 0 فشل |
| `assembleWhatsappDebug` + `assembleBusinessDebug` | ✅ نجح البناءان (‏37.01 MiB لكل APK debug) |
| `spotlessCheck` + `detekt` | ✅ نظيفان |
| `lintWhatsappDebug` + `lintBusinessDebug` | ✅ لا أخطاء جديدة (الفروق داخل `lint-baseline.xml`) |
| مدقق التوافق | ✅ 13/13 اختبار mutation + `compatibility.json` سليم + `docs/COMPATIBILITY.md` محدَّث |
| بوابة T04 | lint ‏313/419 ✅ — `!!` ‏27/60 ✅ — اختبارات 1440 ≥ الأساس ✅ — APK ‏+3.1% (أُعيد توليد `baseline.json`، مسجَّل في جدول القرارات) |

**أعطال حقيقية كشفتها الاختبارات أثناء التحقق وأُصلحت (لا تُخفى):** عدم تقدّم قارئ `MiniJson` (كان يقرأ كل مستند سليم فيفشل)، رفض معرّفات features بشرطة سفلية، السماح بإكمال تنزيل لم يبدأ، تمديد القوالب أكثر من تمريرة واحدة، حد أدنى لهدف اللمس في الوصولية، تصادم بادئة مساحة أسماء الحزم (`com.whatsapp` مع `com.whatsapp.w4b`)، عدم ربط تجزئة الحساب بالحزمة، انقسام مفتاح سجل الحسابات عند أول نقطة بدل آخرها، ساعة كاش النسخ النصي، وعزل سجل تدقيق قواعد الأتمتة بين محرّكين.

---

## 4) العلاقة بالخطة الأصلية

- `docs/MASTER_PLAN.md` يبقى مرجع `T00 → T75` وDefinition of Done.
- هذه الخارطة توسعة بعد 2.0 ولا تعدّل أي قاعدة من قواعده: نفس بوابة T04، نفس قواعد الإصدار والتوقيع، ونفس شرط «لا support يُعلَن بلا دليل resolvers».
