from pathlib import Path

root = Path("app/src/main/java/com/tombad8900/cinemastreamnative")
client = root / "tmdb/TmdbClient.kt"
home = root / "tmdb/TmdbHomeScreen.kt"
main = root / "MainActivity.kt"
manifest = Path("app/src/main/AndroidManifest.xml")
app = root / "CinemaStreamApplication.kt"

c, h, m, man = [p.read_text() for p in (client, home, main, manifest)]

checks = [
    ("كاش TMDB", "private val homeCache = ConcurrentHashMap" in c),
    ("دالة تحميل TMDB", "suspend fun fetchHome(" in c),
    ("الشاشة الرئيسية", "TmdbClient.fetchHome(apiKey, filter)" in h),
    ("تمرير الرئيسية", ".then(if (selectedTab == 1) Modifier else Modifier.verticalScroll(rememberScrollState()))" in m),
    ("Manifest", "<application" in man),
]
for name, ok in checks:
    if not ok:
        raise SystemExit("فشل فحص " + name + ". لم يتم تعديل الملفات.")

if app.exists():
    raise SystemExit("ملف CinemaStreamApplication.kt موجود بالفعل. أوقفنا التعديل لحمايته.")

# نسخ احتياطية
for p in (client, home, main, manifest):
    p.with_suffix(p.suffix + ".before_cache_fix").write_text(p.read_text())

# إضافة Context
c = c.replace(
    "package com.tombad8900.cinemastreamnative.tmdb",
    "package com.tombad8900.cinemastreamnative.tmdb\n\nimport android.content.Context",
    1
)

# استبدال كاش الذاكرة بكاش يدعم التخزين الدائم
start = c.index("    fun cachedHome(apiKey: String, filter: String): TmdbHomeData?")
end = c.index("    private const val BASE_URL", start)

cache = r'''
    private const val HOME_PREFS = "tmdb_home_cache"

    private fun diskKey(apiKey: String, filter: String) =
        "home_${apiKey.hashCode()}_${filter.lowercase()}"

    fun cachedHome(context: Context, apiKey: String, filter: String): TmdbHomeData? {
        if (apiKey.isBlank()) return null
        val key = "${apiKey.hashCode()}:${filter.lowercase()}"
        homeCache[key]?.let { return it.second }

        val raw = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
            .getString(diskKey(apiKey, filter), null) ?: return null
        val result = runCatching { decodeHome(JSONObject(raw)) }.getOrNull() ?: return null
        homeCache[key] = System.currentTimeMillis() to result
        return result
    }

    private fun encodeMedia(items: List<TmdbMedia>): org.json.JSONArray {
        val array = org.json.JSONArray()
        items.forEach { media ->
            array.put(JSONObject().apply {
                put("id", media.id)
                put("title", media.title)
                put("type", media.mediaType)
                put("year", media.year)
                put("overview", media.overview)
                put("poster", media.posterPath ?: JSONObject.NULL)
                put("rating", media.rating)
                put("popularity", media.popularity)
            })
        }
        return array
    }

    private fun decodeMedia(array: org.json.JSONArray?): List<TmdbMedia> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (type != "movie" && type != "tv") continue
                val poster = item.opt("poster")
                add(TmdbMedia(
                    id = item.optInt("id"),
                    title = item.optString("title"),
                    mediaType = type,
                    year = item.optString("year"),
                    overview = item.optString("overview"),
                    posterPath = if (poster == null || poster === JSONObject.NULL) null else poster.toString(),
                    rating = item.optDouble("rating"),
                    popularity = item.optDouble("popularity")
                ))
            }
        }
    }

    private fun encodeHome(data: TmdbHomeData): JSONObject {
        val companies = org.json.JSONArray()
        data.companies.forEach { company ->
            companies.put(JSONObject().apply {
                put("name", company.name)
                put("items", encodeMedia(company.items))
            })
        }
        return JSONObject().apply {
            put("trending", encodeMedia(data.trending))
            put("popular", encodeMedia(data.popular))
            put("top", encodeMedia(data.topRated))
            put("companies", companies)
        }
    }

    private fun decodeHome(json: JSONObject): TmdbHomeData {
        val array = json.optJSONArray("companies")
        val companies = buildList {
            if (array != null) for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(TmdbCompanySection(
                    item.optString("name"),
                    decodeMedia(item.optJSONArray("items"))
                ))
            }
        }
        return TmdbHomeData(
            trending = decodeMedia(json.optJSONArray("trending")),
            popular = decodeMedia(json.optJSONArray("popular")),
            topRated = decodeMedia(json.optJSONArray("top")),
            companies = companies
        )
    }

'''
c = c[:start] + cache + c[end:]

# تمرير Context لحفظ البيانات بعد تحميلها
c = c.replace(
    "suspend fun fetchHome(\n        apiKey: String,",
    "suspend fun fetchHome(\n        context: Context,\n        apiKey: String,",
    1
)

# تحديد نوع نتائج الترند
old = "val trendingJob = async { fetchList(apiKey, trendingPath) }"
new = "val trendingType = if (type == \"all\") null else type\n            val trendingJob = async { fetchList(apiKey, trendingPath, trendingType) }"
if old not in c:
    raise SystemExit("لم أجد سطر تحميل الترند.")
c = c.replace(old, new, 1)

old = "homeCache[cacheKey] = System.currentTimeMillis() to result\n        result"
new = '''homeCache[cacheKey] = System.currentTimeMillis() to result
        runCatching {
            context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(diskKey(apiKey, type), encodeHome(result).toString())
                .apply()
        }
        result'''
if old not in c:
    raise SystemExit("لم أجد مكان حفظ نتيجة TMDB.")
c = c.replace(old, new, 1)

# عرض الكاش فورًا ثم تحديثه
h = h.replace(
    "TmdbClient.cachedHome(apiKey, filter)",
    "TmdbClient.cachedHome(context, apiKey, filter)",
    1
)

old = '''    LaunchedEffect(apiKey, filter) {
        if (apiKey.isBlank()) {
            data = null
            error = "أضف مفتاح TMDB من الإعدادات أولًا."
        } else if (data == null) {
            loading = true
            error = ""
            try {
                data = TmdbClient.fetchHome(apiKey, filter)
            } catch (e: Exception) {
                error = e.message ?: "تعذر تحميل المحتوى."
            } finally {
                loading = false
            }
        }
    }'''

new = '''    LaunchedEffect(apiKey, filter) {
        if (apiKey.isBlank()) {
            data = null
            error = "أضف مفتاح TMDB من الإعدادات أولًا."
        } else {
            loading = data == null
            error = ""
            try {
                data = TmdbClient.fetchHome(context, apiKey, filter)
            } catch (e: Exception) {
                if (data == null) error = e.message ?: "تعذر تحميل المحتوى."
            } finally {
                loading = false
            }
        }
    }'''

if old not in h:
    raise SystemExit("لم أتمكن من مطابقة كود تحميل الرئيسية.")
h = h.replace(old, new, 1)

# منع تداخل التمرير عند فتح التفاصيل من الرئيسية
m = m.replace(
    ".then(if (selectedTab == 1) Modifier else Modifier.verticalScroll(rememberScrollState()))",
    ".then(if (selectedTab == 1 || selectedMedia != null) Modifier else Modifier.verticalScroll(rememberScrollState()))",
    1
)

# كاش الصور باستخدام Coil
app.write_text('''package com.tombad8900.cinemastreamnative

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class CinemaStreamApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val appContext = this
        return ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("cinemastream_image_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L)
                    .build()
            }
            .crossfade(120)
            .build()
    }
}
''')

man = man.replace(
    "<application",
    '<application android:name=".CinemaStreamApplication"',
    1
)

client.write_text(c)
home.write_text(h)
main.write_text(m)
manifest.write_text(man)

print("اكتملت التعديلات والنسخ الاحتياطية.")
