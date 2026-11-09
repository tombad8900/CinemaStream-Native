# حالة المشروع (CinemaStream Native)

## الوضع الحالي
تطبيق Android (Kotlin + Jetpack Compose + Media3). المتصفح الداخلي شغال والتطبيق يتبني ويتثبت (debug) على Termux.

## الأقسام الرئيسية
- `browser/BrowserScreen.kt`: المتصفح الداخلي (WebView) مع التقاط روابط الفيديو (HLS/DASH/MP4) والترجمات وتشغيلها بالمشغل الأصلي.
- `PlayerActivity.kt`: المشغل الأصلي (Media3).
- `iptv/` و`IptvManagerScreen.kt`: إدارة قوائم IPTV (كلمات المرور مشفرة).
- `sources/`, `tmdb/`: مصادر البث وبيانات TMDB.
- `res/`: أيقونة التطبيق (Adaptive Icon زجاجية بحرف m).

## المتصفح الداخلي
- حظر الإعلانات: قائمة نطاقات + فلتر مسارات + حظر popups لنطاقات مختلفة عن نطاق الصفحة.
- معالجة الأعطال: onRenderProcessGone، تنظيف WebView عند الخروج.
- زر Back، إصلاح ملء الشاشة، third-party cookies، mixed content.
- كشف الترجمة العربية بشكل أدق.

## البناء (Termux)
./gradlew assembleDebug
الناتج: app/build/outputs/apk/debug/app-debug.apk

## ما تبقى (مقترحات)
- قائمة حظر أكبر من ملف assets
- إخفاء عناصر الإعلانات بـCSS
- نسخة release موقّعة
