# الوحدات والبيانات

## 1. هيكل Gradle المقترح
هذا هيكل منطقي مبدئي؛ يجوز دمج وحدات صغيرة في البداية لتقليل تعقيد Gradle على Termux، ثم فصلها عندما تظهر حاجة حقيقية.

\`\`\`text
CinemaStream-Native/
├── app/
├── core/
│   ├── common/
│   ├── model/
│   ├── database/
│   ├── network/
│   ├── storage/
│   └── security/
├── data/
│   ├── tmdb/
│   ├── library/
│   ├── history/
│   └── preferences/
├── feature/
│   ├── home/
│   ├── discover/
│   ├── details/
│   ├── library/
│   ├── browser/
│   ├── player/
│   ├── subtitles/
│   ├── iptv/
│   └── settings/
└── engine/
    ├── stream/
    ├── subtitle/
    │   ├── yify/
    │   ├── tvsubtitles/
    │   ├── moviesubtitles/
    │   └── subtitlecat/
    ├── browser/
    ├── adblock/
    └── diagnostics/
\`\`\`

## 2. مسؤولية الوحدات
- app: نقطة الدخول، إعداد الاعتماديات، التنقل العام وربط الميزات.
- core:model: نماذج مشتركة خالصة.
- core:database: Room وعمليات الترحيل.
- core:network: إعداد عميل HTTP وسياسات المهلة والاعتراضات العامة.
- core:storage: ملفات التطبيق المؤقتة والدائمة.
- core:security: تنقيح السجلات والتحقق من الملفات وحماية الأسرار.
- data:tmdb: اتصالات TMDB وتحويل الاستجابات.
- data:library: تنفيذ المفضلة وقائمة المشاهدة والتقييمات.
- feature:*: شاشات وحالات العرض.
- engine:browser: تكامل GeckoView ودورة حياة الجلسات.
- engine:stream: اكتشاف المرشحين وتوحيدهم.
- engine:subtitle:*: موصل مستقل لكل مصدر ترجمة.
- engine:player: يمكن أن يضاف كوحدة مستقلة أو يبقى ضمن feature:player في النسخة الأولى؛ يحسم ذلك عند إنشاء الهيكل.
- engine:iptv: تحليل M3U وXMLTV وتوحيد القنوات والبرامج.
- engine:adblock: قواعد وسياسات الحجب.
- engine:diagnostics: جمع الأخطاء المحلية وتفسيرها.

## 3. نماذج البيانات الأساسية
**MediaItem:** id الداخلي، providerId مثل TMDB ID، mediaType، title وoriginalTitle، overview، releaseDate أو year، posterPath وbackdropPath، genres، metadataUpdatedAt.

**LibraryEntry:** mediaItemId، isFavorite، inWatchlist، watchedState، userRating، addedAt وupdatedAt.

**WatchProgress:** mediaItemId، seasonNumber وepisodeNumber عند الحاجة، positionMs، durationMs، lastWatchedAt، playbackSourceId اختياري دون أسرار المصدر.

**StreamCandidate:** id، uri، mimeType، streamType إن أمكن تحديده، headers محدودة ومؤقتة عند الضرورة، sourcePage، confidence، validationState، discoveredAt.

**SubtitleResult:** providerId، title، languageCode، seasonNumber وepisodeNumber، releaseName، format، downloadReference، uploader، machineTranslated عند توفره، matchScore.

**IptvChannel:** listId، channelId، name، groupTitle، logoUri، streamUri، tvgId، sortOrder.

**BrowserSessionMetadata:** بيانات تعريف غير حساسة فقط: نوع الجلسة، وقت الإنشاء، وحالة الجلسة. بيانات GeckoView الفعلية تظل ضمن آلية تخزين المحرك ولا تُنسخ إلى قاعدة المكتبة.

## 4. التخزين المحلي
- Room للبيانات المنظمة.
- DataStore لتفضيلات التطبيق.
- مساحة داخلية خاصة للتنزيلات والترجمات المؤقتة.
- Cache مستقل للصور والبيانات.
- عدم وضع الملفات الحساسة أو بيانات الجلسات في مجلد عام.
- تحديد سياسة تنظيف واضحة للملفات المؤقتة.

## 5. استيراد وتصدير JSON
- رقم schemaVersion إلزامي.
- تحقق من الأنواع والحدود قبل الاستيراد.
- الاستيراد داخل معاملة قاعدة بيانات قدر الإمكان.
- سياسة واضحة للتعارضات والتكرارات.
- لا يتضمن التصدير مفاتيح API أو Cookies أو Tokens أو بيانات المتصفح الخاصة.
- دعم الترقية من إصدارات JSON لاحقة دون كسر البيانات القديمة.

## 6. سياسة الشبكة والتخزين المؤقت
- مهلات اتصال وقراءة محددة.
- إلغاء الطلبات غير اللازمة.
- معالجة HTTP errors بصورة موحدة.
- Cache-Control واحترام شروط المزود.
- عدم تخزين بيانات شخصية أو استجابات حساسة في Cache مشترك.
- فصل عميل TMDB عن عميل مصادر الترجمة إذا اختلفت سياسات الرؤوس أو الثقة.
