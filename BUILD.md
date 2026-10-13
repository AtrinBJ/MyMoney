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

ساخت APK/AAB نسخه نهایی و امضا در مرحله ۷ به این سند اضافه می‌شود.
