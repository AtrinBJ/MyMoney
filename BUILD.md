# راهنمای نصب، ساخت و اجرا

## پیش‌نیازها
| ابزار | نسخه | توضیح |
|---|---|---|
| JDK | 21 (Temurin) | Gradle با آن اجرا می‌شود؛ خروجی bytecode هدف 17 است |
| Android SDK | platform 36 و 37، build-tools 36.0.0، platform-tools | |
| Gradle | 9.8.1 | از طریق wrapper (`gradlew`) — نصب جدا لازم نیست |
| Android Studio (اختیاری) | نسخه پایدار 2026.2 یا جدیدتر | برای باز کردن پروژه و اجرا روی گوشی |

روی این سیستم نصب شده‌اند در:
- JDK: `C:\Users\at690\Android\jdk-21`
- SDK: `C:\Users\at690\AppData\Local\Android\Sdk` (همان مسیر پیش‌فرض Android Studio)

## نکته شبکه (مهم)
از شبکه این سیستم `dl.google.com` برای فایل‌های اندروید پاسخ **404** می‌دهد. CDN رسمی گوگل `edgedl.me.gvt1.com` همان فایل‌ها را ارائه می‌کند:
- **وابستگی‌های Gradle:** در `settings.gradle.kts` بعد از `google()` مخزن `https://edgedl.me.gvt1.com/android/maven2/` اضافه شده است؛ کاری لازم نیست.
- **وضعیت ۱۴ مهر ۱۴۰۵ (2026-10-14):** `edgedl.me.gvt1.com` به همه درخواست‌ها پاسخ **403** می‌دهد. ساخت با کش Gradle کار می‌کند (همه وابستگی‌های فعلی کش شده‌اند)؛ افزودن یا تغییر نسخه کتابخانه‌های گوگل تا رفع این مشکل ممکن نیست. وابستگی‌های Maven Central (مثل Dagger و Tink) مشکلی ندارند.
- **sdkmanager:** قبل از اجرا متغیر زیر را تنظیم کنید:
  ```powershell
  $env:SDK_TEST_BASE_URL = "https://edgedl.me.gvt1.com/android/repository/"
  ```
- **Android Studio:** اگر SDK Manager داخل Studio خطای دانلود داد، از همان sdkmanager خط فرمان با متغیر بالا استفاده کنید.

## ساخت از خط فرمان (PowerShell)
```powershell
$env:JAVA_HOME = "C:\Users\at690\Android\jdk-21"
cd "C:\Users\at690\Downloads\claude code\MyMoney"
.\gradlew.bat assembleDebug
```
خروجی: `app\build\outputs\apk\debug\app-debug.apk` (شناسه `com.mymoney.app.debug`، جدا از نسخه release نصب می‌شود).

## تست‌ها
```powershell
.\gradlew.bat :core:domain:test                 # قواعد مالی، تقویم، پول (JUnit 5 + property-based)
.\gradlew.bat testDebugUnitTest                 # Robolectric: داده، تقویم در برابر ICU، رابط کاربری
.\gradlew.bat lintDebug                         # Android lint
.\gradlew.bat connectedDebugAndroidTest         # فقط با گوشی متصل (رمزنگاری DB، مهاجرت‌ها)
```

## نصب روی Galaxy A55
1. در گوشی: Settings › About phone › Software information › هفت بار روی *Build number* بزنید تا Developer options فعال شود.
2. Developer options › **USB debugging** را روشن کنید و گوشی را با کابل وصل کنید (پیام اجازه را تأیید کنید).
3. نصب:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
   ```

## باز کردن در Android Studio
File › Open › پوشه `MyMoney`. Studio خودش wrapper و SDK را پیدا می‌کند (فایل `local.properties` مسیر SDK را دارد). JDK پروژه: Settings › Build Tools › Gradle › Gradle JDK = JDK 21 (یا JBR داخلی Studio).

## ساخت نسخه نهایی (APK و AAB)
```powershell
.\gradlew.bat dist
```
همه خروجی‌ها در پوشه `dist\` قرار می‌گیرند:

| فایل | کاربرد |
|---|---|
| `MyMoney-<نسخه>-debug.apk` | نسخه آزمایشی (`com.mymoney.app.debug`)، شامل بخش Developer و ابزار داده آزمایشی؛ کنار نسخه اصلی نصب می‌شود |
| `MyMoney-<نسخه>-release[-DEBUGSIGNED].apk` | نسخه نهایی، با R8 و حذف لاگ، بدون هیچ داده یا ابزار آزمایشی |
| `MyMoney-<نسخه>-release[-DEBUGSIGNED].aab` | برای انتشار در فروشگاه (Google Play یا Bazaar/Myket با پشتیبانی AAB) |
| `MyMoney-<نسخه>-mapping.txt` | برای خواندن stack trace خطاهای release (`retrace`) — آن را نگه دارید ولی منتشر نکنید |

### امضا
- **بدون `keystore.properties`:**
  - نسخه release با کلید debug همین رایانه امضا می‌شود و نامش `-DEBUGSIGNED` می‌گیرد.
  - برای نصب و آزمون کافی است، ولی **برای انتشار مناسب نیست**: هر رایانه کلید debug متفاوتی دارد و به‌روزرسانی بین آن‌ها ممکن نیست.
- **کلید انتشار:** یک بار ساخته و در جایی امن (بیرون از پروژه و با پشتیبان) نگه داشته می‌شود. **اگر گم شود، به‌روزرسانی برنامه منتشرشده دیگر ممکن نیست.**
  ```powershell
  & "$env:JAVA_HOMEin\keytool.exe" -genkeypair -v -keystore D:\keys\mymoney-release.jks -alias mymoney -keyalg RSA -keysize 4096 -validity 10000
  ```
- **فایل `keystore.properties`:** در ریشه پروژه ساخته می‌شود و در `.gitignore` است، پس هرگز commit نمی‌شود:
  ```properties
  storeFile=D:/keys/mymoney-release.jks
  storePassword=...
  keyAlias=mymoney
  keyPassword=...
  ```
- **ساخت امضاشده:** پس از آن، `.\gradlew.bat dist` خروجی امضاشده با همین کلید و بدون پسوند `-DEBUGSIGNED` می‌سازد.
- **بررسی امضا:**
  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdkuild-tools.0.0pksigner.bat" verify --print-certs dist\MyMoney-1.0.0-rc.1-release.apk
  ```

### نصب نسخه release روی گوشی
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-toolsdb.exe" install -r dist\MyMoney-1.0.0-rc.1-release-DEBUGSIGNED.apk
```
- **حذف پیش از نصب:** اگر نسخه‌ای با کلید دیگر نصب شده باشد، اندروید نصب را رد می‌کند و باید ابتدا آن را حذف کنید. حذف برنامه همه داده‌اش را پاک می‌کند، پس پیش از آن از داخل برنامه پشتیبان بگیرید.
- **نسخه debug:** شناسه جداگانه دارد و با نسخه release تداخلی ندارد.

### نسخه‌گذاری
`versionName` و `versionCode` در `app/build.gradle.kts` تعریف شده‌اند (`appVersionName`). `versionCode` باید با هر انتشار بزرگ‌تر شود.

## سنجش عملکرد و آزمون روی گوشی
- **سنجش عملکرد:**
  - `PERF=1 .\gradlew.bat :app:testDebugUnitTest --tests "*PerformanceBenchmarkTest*"`
  - گزارش در `appuild\perf-report.txt` نوشته می‌شود.
- **آزمون روی گوشی:**
  - با A55 متصل، `.\gradlew.bat connectedDebugAndroidTest` را اجرا کنید.
  - سپس [docs/QA_CHECKLIST.md](docs/QA_CHECKLIST.md) را مرور کنید.
