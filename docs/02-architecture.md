# ۲. معماری نرم‌افزار

## ۲.۱ نمای کلی
```
┌─────────────────────────────── :app ───────────────────────────────┐
│  MainActivity (AppCompatActivity) → LockGate → NavHost (5 tabs)    │
│  feature/<x>/  XxxScreen (Compose, stateless)                      │
│                XxxViewModel (Hilt) → StateFlow<XxxUiState>         │
│  reminders/ (HiltWorker)   lock/ (AppLockManager)                  │
└───────────────┬──────────────────────────────┬─────────────────────┘
                │ uses                         │ uses
┌───────────────▼──────────────┐   ┌───────────▼─────────────────────┐
│ :core:designsystem (Android) │   │ :core:data (Android library)    │
│ Theme, tokens, Vazirmatn,    │   │ Room+SQLCipher, DAO, Entities,  │
│ components, charts,          │   │ RepositoryImpl, DataStore,      │
│ JalaliDatePicker             │   │ Tink/Keystore, Backup, CSV I/O, │
└───────────────┬──────────────┘   │ Attachments                     │
                │                  └───────────┬─────────────────────┘
                │ depends on                   │ implements
┌───────────────▼──────────────────────────────▼─────────────────────┐
│ :core:domain  (pure Kotlin/JVM — no Android, no Compose)           │
│ money · calendar · model · rules · calc · insights · csv ·         │
│ recurrence · repository (interfaces) · usecase                     │
└────────────────────────────────────────────────────────────────────┘
```
**جهت وابستگی همیشه به سمت داخل است:** `app` به `data` و `designsystem` وابسته است، و هر سه به `domain` وابسته‌اند. `domain` به هیچ ماژولی وابسته نیست.

## ۲.۲ چرا این معماری
| تصمیم | دلیل |
|---|---|
| **Clean Architecture سه‌لایه** (domain / data / presentation) | منطق مالی، که حساس‌ترین بخش است، در ماژول JVM خالص قرار دارد. Gradle اجازه نمی‌دهد به `android.*` یا Compose دسترسی داشته باشد. تست‌هایش در چند ثانیه و بدون شبیه‌ساز اجرا می‌شوند. |
| **MVVM** با یک `StateFlow<UiState>` در هر صفحه | جریان یک‌طرفه داده (UDF). وضعیت پس از چرخش صفحه حفظ می‌شود و Composableها فقط وضعیت را رسم می‌کنند. |
| **فیچرها به‌صورت package داخل `:app`** و نه ماژول جدا | برای یک پروژه تک‌نفره، ۲۰ ماژول فیچری یعنی سربار build-logic، زمان پیکربندی و پیچیدگی. مرز حیاتی (domain) فیزیکی است. اگر لازم شد، فیچرها با جابه‌جایی package به ماژول تبدیل می‌شوند، چون وابستگی متقابل ندارند. |
| **Use case فقط جایی که ارزش دارد** | عملیات چندمرحله‌ای و اتمی، مثل `RecordTransfer` و `RecordRefund`، use case دارند. خواندن‌های ساده مستقیماً از repository انجام می‌شوند تا لایه‌های بی‌فایده ساخته نشود. |
| **Repository interface در domain** | پیاده‌سازی Room قابل تعویض است، مثلاً برای همگام‌سازی آینده، و در تست‌های domain از fake استفاده می‌شود. |
| **Hilt** | استاندارد اندروید است، با ViewModel، WorkManager و scopeها یکپارچه است و با KSP سریع کار می‌کند. |
| **Offline-first** | تنها منبع حقیقت دیتابیس محلی است. لایه همگام‌سازی آینده فقط یک «مصرف‌کننده/تولیدکننده» دیگر برای repositoryها خواهد بود. |

## ۲.۳ جریان داده (نمونه: ثبت هزینه)
1. `AddEntryScreen` رویداد `onSave` را به `AddEntryViewModel` می‌فرستد.
2. ViewModel فرم را به `EntryDraft` در domain تبدیل می‌کند (مبلغ با `AmountParser` و بر اساس واحد ریال یا تومان).
3. `RecordEntryUseCase` ابتدا `EntryValidator` را اجرا می‌کند و اگر قاعده‌ای نقض شده باشد `DomainError` برمی‌گرداند.
4. `LedgerRepository.saveGroup(group)` در `db.withTransaction { … }` همه legها، splitها، tagها و `change_log` را ثبت می‌کند. اگر `groupId` قبلاً ثبت شده باشد، نتیجه `AlreadySaved` است (idempotency).
5. Room تغییر جدول را اعلام می‌کند و Flowهای داشبورد و فهرست خودکار دوباره منتشر می‌شوند.

## ۲.۴ مدیریت وضعیت و خطا
- `UiState` هر صفحه یک `data class` تغییرناپذیر است و فیلدهای `isLoading`، `content?` و `error?` یا یک sealed interface دارد.
- رویدادهای یک‌باره، مثل snackbar یا ناوبری، با `Channel<UiEvent>` به‌صورت `receiveAsFlow` منتقل می‌شوند.
- خطاهای قابل پیش‌بینی به‌صورت `sealed interface DomainError` تعریف شده‌اند (`InvalidAmount`، `SameAccountTransfer`، `MissingRate`، `Overflow`، `NotFound`، …) و پیام ترجمه‌شده دارند. استثناهای پیش‌بینی‌نشده در ViewModel گرفته می‌شوند و پیام عمومی نمایش داده می‌شود. جزئیات فقط در لاگ debug ثبت می‌شود و هیچ مبلغی در لاگ نمی‌آید.
- همه عملیات IO روی `Dispatchers.IO` اجرا می‌شوند (Room این کار را به‌صورت داخلی انجام می‌دهد) و محاسبات سنگین گزارش روی `Dispatchers.Default`. Dispatcherها با Hilt تزریق می‌شوند تا تست‌پذیر باشند.

## ۲.۵ ناوبری
- Navigation Compose 2.10 با routeهای `@Serializable`.
- ۵ تب: `Home`، `Transactions`، `Planning`، `Wealth`، `Reports`. هر تب back stack خودش را دارد و `saveState/restoreState` فعال است.
- `LockGate` قبل از NavHost قرار دارد: تا وقتی برنامه قفل است، محتوا ساخته نمی‌شود و DB هم باز نمی‌شود.
- Onboarding یک گراف جدا است و پس از تکمیل، از back stack حذف می‌شود.

## ۲.۶ امنیت در معماری
- `DbKeyProvider`: یک کلید خام ۳۲ بایتی تصادفی برای SQLCipher می‌سازد، آن را با Tink AEAD (AES-256-GCM) رمز می‌کند و keyset خود Tink را با یک master key در Android Keystore می‌پوشاند (`AndroidKeysetManager`).
- `MyMoneyDatabase` با `SupportOpenHelperFactory(rawKey)` ساخته می‌شود. در تست‌های JVM، factory عادی در حافظه جایگزین آن می‌شود.
- جزئیات کامل در `SECURITY.md` آمده است.

## ۲.۷ پس‌زمینه و باتری
- فقط یک `PeriodicWorkRequest` روزانه به نام `DailyMaintenanceWorker` وجود دارد که این کارها را انجام می‌دهد:
  - اعلان سررسیدها
  - هشدار بودجه
  - ثبت خودکار قوانینی که کاربر صریحاً AUTO_POST کرده
  - پاک‌سازی سطل زباله پس از ۳۰ روز
- همه این کارها idempotent هستند و هنگام باز شدن برنامه هم سبک اجرا می‌شوند.
- از exact alarm، foreground service، wakelock یا شبکه استفاده نمی‌شود.

## ۲.۸ قابلیت توسعه در آینده
| آینده | نقطه اتصال آماده |
|---|---|
| همگام‌سازی ابری | UUIDv7، فیلدهای `updatedAt` و `deletedAt`، `change_log`، repositoryهای انتزاعی |
| نرخ ارز آنلاین | `RateProvider` + `exchange_rate.source` + نمایش تاریخ و منبع نرخ |
| چند پروفایل | هر پروفایل یک فایل DB جدا با کلید جدا دارد و `DatabaseProvider` قابل تعویض است |
| تحلیل ابری | `InsightEngine` محلی جدا است و یک `RemoteInsightProvider` اختیاری با رضایت صریح به آن اضافه می‌شود |
| تقویم‌های دیگر | interface با نام `CalendarSystem` |

## ۲.۹ سازگاری و نسخه‌ها
- `minSdk 26`، `targetSdk 36`، و `compileSdk` برابر 36 یا 37 (طبق الزام AndroidX)
- JDK 21 برای ساخت و Java/Kotlin target 17
- abiها در release: `arm64-v8a` (A55) و `x86_64` (شبیه‌ساز)

## ۲.۱۰ نکته محیط ساخت
از شبکه این سیستم، `dl.google.com` برای مسیرهای اندروید پاسخ 404 می‌دهد. CDN رسمی گوگل یعنی `edgedl.me.gvt1.com` همان فایل‌ها را ارائه می‌کند. به همین دلیل:
- `settings.gradle.kts` پس از `google()` یک مخزن جایگزین روی همان CDN دارد. این مخزن با `content { includeGroupByRegex(...) }` به گروه‌های گوگل و اندروید محدود شده است.
- برای `sdkmanager` متغیر `SDK_TEST_BASE_URL=https://edgedl.me.gvt1.com/android/repository/` تنظیم می‌شود. جزئیات در `BUILD.md` آمده است.
