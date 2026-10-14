# MyMoney — مدیریت مالی شخصی (اندروید)

اپلیکیشن آفلاین، امن و دوزبانه (فارسی RTL / English LTR) برای مدیریت حساب‌ها، تراکنش‌ها، بودجه، پرداخت‌های دوره‌ای، بدهی و مطالبات، دارایی‌ها، اهداف پس‌انداز و گزارش‌های مالی.

- Kotlin · Jetpack Compose · Material 3 · Clean Architecture + MVVM · Room + SQLCipher · Hilt · DataStore · WorkManager
- بدون مجوز اینترنت؛ هیچ داده‌ای از دستگاه خارج نمی‌شود مگر با خروجی/پشتیبان صریح کاربر.
- دستگاه مرجع: Samsung Galaxy A55 (Android 14+).

## مستندات
| سند | محتوا |
|---|---|
| [docs/01-product-scope.md](docs/01-product-scope.md) | شرح محصول و محدوده نسخه ۱ |
| [docs/02-architecture.md](docs/02-architecture.md) | معماری و دلایل انتخاب |
| [docs/03-data-model.md](docs/03-data-model.md) | مدل داده و مهاجرت‌ها |
| [docs/04-financial-business-rules.md](docs/04-financial-business-rules.md) | **Financial Business Rules** |
| [docs/05-screens-and-flows.md](docs/05-screens-and-flows.md) | نقشه صفحات و مسیرهای کاربری |
| [docs/06-design-system.md](docs/06-design-system.md) | Design System |
| [docs/07-test-plan.md](docs/07-test-plan.md) | برنامه تست |
| [docs/08-decisions-and-risks.md](docs/08-decisions-and-risks.md) | تصمیم‌های طراحی و ریسک‌ها |
| [docs/FEATURES_STATUS.md](docs/FEATURES_STATUS.md) | قابلیت‌های تکمیل‌شده و باقی‌مانده |

راهنمای ساخت: [BUILD.md](BUILD.md). امنیت: [docs/SECURITY.md](docs/SECURITY.md). پشتیبان‌گیری، بازیابی و ورود و خروج داده: [docs/BACKUP_RESTORE.md](docs/BACKUP_RESTORE.md). چک‌لیست کیفیت: [docs/QA_CHECKLIST.md](docs/QA_CHECKLIST.md).

## مجوزهای شخص ثالث
- فونت Vazirmatn — SIL Open Font License 1.1 (`core/designsystem/src/main/assets/licenses/Vazirmatn-OFL.txt`)
- الگوریتم تبدیل تقویم جلالی برگرفته از jalaali-js — MIT
