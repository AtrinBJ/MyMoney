# ۷. برنامه تست

## ۷.۱ لایه‌ها و ابزارها
| لایه | ابزار | محل اجرا | زمان تقریبی |
|---|---|---|---|
| Domain (قواعد، محاسبات، تقویم، CSV) | JUnit 5 + kotest-property + kotlinx-coroutines-test | JVM — `:core:domain:test` | ثانیه‌ها |
| Data (DAO، Repository، Backup، CSV I/O، Migration، Jalali↔ICU) | JUnit 4 + Robolectric + Room in-memory + Turbine | JVM — `:core:data:testDebugUnitTest` | دقیقه |
| UI (صفحات کلیدی، RTL، وضعیت‌ها، چرخش) | Compose UI Test روی Robolectric | JVM — `:app:testDebugUnitTest` | دقیقه |
| Instrumented (SQLCipher، مهاجرت روی دستگاه، smoke) | AndroidJUnit4 + MigrationTestHelper | Galaxy A55 با USB — `connectedDebugAndroidTest` | دقیقه |

SQLCipher کتابخانه native اندروید است و روی JVM بارگذاری نمی‌شود؛ تست‌های JVM از Room معمولی (بدون رمز) با همان DAOها استفاده می‌کنند و رمزنگاری فقط در تست instrumented بررسی می‌شود.

## ۷.۲ نگاشت سناریوهای الزامی ← تست‌ها
| سناریو | تست |
|---|---|
| ثبت درآمد و افزایش موجودی | `BalanceCalculatorTest.income_increases_balance`, `LedgerRepositoryTest.record_income` |
| ثبت هزینه و کاهش موجودی | `…expense_decreases_balance` |
| انتقال بین دو حساب | `TransferRulesTest.same_currency_transfer`, property `transfersNeverChangeIncomeOrExpense` |
| انتقال بین دو ارز | `TransferRulesTest.fx_transfer_records_two_legs_and_implied_rate` |
| اعمال کارمزد | `TransferRulesTest.fee_is_separate_expense` |
| ثبت و بازپرداخت بدهی | `DebtRulesTest.*` |
| ثبت طلب و دریافت | `ReceivableRulesTest.*` |
| برگشت وجه خرید | `RefundRulesTest.*` |
| خرید دارایی / تغییر قیمت | `AssetRulesTest.*`, `NetWorthTest.revaluation_changes_net_worth_not_income` |
| محاسبه ارزش خالص | `NetWorthTest.*`, property `netWorthEqualsAccountsPlusAssets` |
| بودجه و هشدارها | `BudgetCalculatorTest.*`, `BudgetAlertTest.*` |
| گزارش ماهانه در مرز ماه و سال | `PeriodTest.jalali_month_boundaries`, `gregorian_year_boundary`, `IncomeStatementTest.entries_on_boundary_days` |
| تراکنش تکرارشونده | `RecurrenceTest.*`, `RecurringPostingTest.auto_post_is_idempotent` |
| ورود و خروج | `CsvParserTest.*`, `CsvImportTest.*`, `CsvExportTest.round_trip` |
| پشتیبان و بازیابی | `BackupRoundTripTest.full_round_trip_preserves_everything`, `encrypted_backup_wrong_password_fails` |
| حالت آفلاین | معماری: مجوز INTERNET وجود ندارد — بررسی خودکار مانیفست نهایی (`ManifestPermissionsTest`) |
| چرخش/بازگشت از پس‌زمینه | `StateRestorationTester` برای فرم ثبت و فیلترها |
| مهاجرت DB بدون ازدست‌رفتن داده | `MigrationTest.migrate1to2_preserves_entries`, `migrate2to3…` |
| داده ناقص/نامعتبر | `EntryValidatorTest.*`, `DataHealthTest.*` |

## ۷.۳ تست‌های مرزی
- صفر، منفی غیرمجاز برای هر kind، 10^15 و 10^15+1، سرریز جمع (`addExact`).
- نرخ ارز ۰، منفی، خیلی بزرگ، نامعتبر (متن)، ناموجود.
- تاریخ نامعتبر (۳۰ اسفند در سال غیرکبیسه، ۳۱ مهر، ۲۹ فوریه غیرکبیسه)، خارج از ۱۹۰۰–۲۲۰۰.
- موجودی ناکافی (هشدار، نه منع).
- ورودی تکراری (همان groupId دو بار؛ همان فایل CSV دو بار).
- تبدیل تومان↔ریال با مقادیر غیرقابل‌تقسیم.
- ارزهای با ۰، ۲ و ۳ رقم اعشار.

## ۷.۴ تست‌های مبتنی بر ویژگی (property-based)
مولد: دنباله تصادفی ۱ تا ۲۰۰ عملیات معتبر (درآمد، هزینه، انتقال هم‌ارز و دوارزی با کارمزد، برگشت، اصلاح، خرید/فروش دارایی، قرض‌دادن/وصول، دریافت وام/بازپرداخت) روی ۲ تا ۶ حساب با ارزهای مختلف. ناوردایی‌ها:
1. Σ موجودی‌ها (به ازای هر ارز) = Σ موجودی اولیه + درآمد − هزینه + برگشت + اصلاح + فروش دارایی − خرید دارایی ± WRITE_OFF (انتقال‌ها صفر).
2. انتقال‌ها هیچ‌گاه درآمد/هزینه را تغییر نمی‌دهند.
3. ارزش خالص = Σ حساب‌ها + Σ دارایی‌ها؛ خرید دارایی با ارزش‌گذاری برابر قیمت خرید، ارزش خالص را فقط به اندازه کارمزد کم می‌کند.
4. اتحاد جریان نقدی (FBR-09) برقرار است.
5. مصرف بودجه = هزینه − برگشت در دسته‌ها.
6. جمع‌های گزارش = جمع‌های داشبورد.
7. تقسیم largest-remainder: جمع اجزا = کل، هر جزء نامنفی.
8. Jalali: `toJalali(fromJalali(y,m,d)) == (y,m,d)` و `fromJalali(toJalali(day)) == day` برای همه روزها.

## ۷.۵ تست تقویم
- مقادیر مرجع: نوروزهای ۱۳۰۰ تا ۱۴۵۰ (تاریخ میلادی ۱ فروردین)، سال‌های کبیسه معروف (۱۳۹۹، ۱۴۰۳ کبیسه؛ ۱۴۰۴ عادی).
- Cross-validation: مقایسه روز به روز با `android.icu.util.Calendar("@calendar=persian")` برای ۱۳۰۰–۱۵۰۰ (Robolectric).

## ۷.۶ عملکرد
- `PerfSeeder` (فقط debug): ۱۰۰٬۰۰۰ تراکنش، ۱۵ حساب، ۵ سال. اندازه‌گیری: زمان کوئری موجودی همه حساب‌ها، داشبورد، گزارش سالانه، صفحه اول فهرست. هدف: داشبورد < ۳۰۰ms روی A55.

## ۷.۷ معیار پذیرش هر مرحله
`assembleDebug` ✔ · همه تست‌های واحد ✔ · `lintDebug` بدون Error ✔ · گزارش مرحله ثبت‌شده.
