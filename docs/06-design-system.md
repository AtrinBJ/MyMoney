# ۶. Design System

> پیاده‌سازی: ماژول `:core:designsystem`. همه رنگ‌ها، ابعاد و فونت‌ها از توکن‌ها خوانده می‌شوند؛ هیچ مقدار رنگ/اندازه خام در فیچرها نوشته نمی‌شود.

## ۶.۱ شخصیت بصری
«آرام، دقیق، قابل اعتماد». سطوح تخت با رنگ tonal، عددها ستاره صفحه‌اند، رنگ فقط برای معنا. نه شلوغ مثل اکسل، نه بازی‌گونه.

## ۶.۲ رنگ‌ها (BrandPalette — قابل تعویض در یک فایل)
| توکن | روشن | تاریک | کاربرد |
|---|---|---|---|
| primary | `#0F6E5F` | `#5FD3B8` | دکمه اصلی، FAB، انتخاب |
| onPrimary | `#FFFFFF` | `#00382F` | |
| primaryContainer | `#A6F2DD` | `#005045` | کارت برجسته (ارزش خالص) |
| secondary | `#4A5BB0` | `#BAC3FF` | نیلی — نمودار دوم، لینک‌ها |
| tertiary | `#9A6A00` | `#F5BD48` | زعفرانی — اهداف، هایلایت |
| background / surface | `#F8FAF9` | `#0B0F0E` | سطح تقریباً سیاه در تاریک → صرفه باتری AMOLED |
| surfaceContainer | `#EEF2F0` | `#161B1A` | کارت‌ها |
| outline | `#6F7975` | `#899390` | |
| error | `#BA1A1A` | `#FFB4AB` | |

**رنگ‌های معنایی (FinanceColors):** income `#1B7F4C`/`#6DDB9B` · expense `#C2413A`/`#FF8A80` · transfer `#2F6FBF`/`#9CC3FF` · warning `#A35C00`/`#FFB86B` · neutral amount = onSurface.
قاعده: رنگ هرگز تنها حامل معنا نیست — مبالغ علامت `+`/`−` و آیکون نوع دارند.
**Dynamic color** (Material You روی One UI) اختیاری و پیش‌فرض خاموش (حفظ هویت و کنتراست معنایی).
کنتراست متن ≥ 4.5:1 در هر دو تم (بررسی‌شده برای جفت‌های بالا).

## ۶.۳ تایپوگرافی
فونت: **Vazirmatn UI** (OFL 1.1) برای فارسی و لاتین — وزن‌ها Regular 400، Medium 500، SemiBold 600، Bold 700.
| سبک | اندازه / ارتفاع خط | وزن | کاربرد |
|---|---|---|---|
| displaySmall | 32/40 | SemiBold | عدد اصلی (ارزش خالص) |
| headlineSmall | 24/32 | SemiBold | عنوان صفحه، مبالغ کارت |
| titleLarge | 20/28 | SemiBold | عنوان بخش |
| titleMedium | 16/24 | Medium | عنوان کارت |
| bodyLarge | 16/26 | Regular | متن اصلی |
| bodyMedium | 14/22 | Regular | ردیف فهرست |
| labelLarge | 14/20 | Medium | دکمه |
| labelMedium | 12/16 | Medium | چیپ، برچسب |
| bodySmall | 12/18 | Regular | توضیح ثانویه |
مبالغ: `MoneyText` همیشه در LTR isolation (U+2066…U+2069) تا علامت منفی و جداکننده در متن RTL جابه‌جا نشوند. ارقام فارسی (۰–۹) یا لاتین طبق تنظیم؛ جداکننده هزارگان `٬` برای فارسی و `,` برای لاتین.

## ۶.۴ ابعاد و شکل
- فاصله‌ها (۴dp grid): `xxs 2 · xs 4 · s 8 · m 12 · l 16 · xl 24 · xxl 32 · xxxl 48`
- حاشیه صفحه: 16dp؛ فاصله کارت‌ها: 12dp
- گوشه‌ها: کارت 20dp · Bottom sheet 28dp · چیپ/فیلد 12dp · دکمه pill کامل · آیکون دسته دایره 40dp
- ارتفاع لمسی حداقل 48dp؛ آیکون 24dp
- سایه: حداقل؛ تمایز با tonal elevation (surfaceContainer Low/High)

## ۶.۵ کامپوننت‌ها
| کامپوننت | رفتار |
|---|---|
| `MoneyText` | قالب‌بندی با `MoneyFormatter`، رنگ معنایی اختیاری، ماسک «••••••» هنگام مخفی‌سازی، `contentDescription` کامل (مبلغ + واحد) |
| `AmountField` | ورودی با ارقام فارسی/لاتین، جداکننده زنده، نمایش واحد (تومان/ریال/EUR)، بدون float |
| `KpiCard` | عنوان، مبلغ، تغییر نسبت به دوره قبل (پیکان + درصد + رنگ معنایی) |
| `BudgetBar` | نوار پیشرفت با آستانه‌ها؛ بالای ۱۰۰٪ رنگ هشدار + برچسب |
| `CategoryIcon` / `CategoryChip` | دایره رنگی + آیکون |
| `AccountRow`, `EntryRow` | ردیف استاندارد فهرست |
| `SectionHeader` | عنوان + اقدام اختیاری («همه») |
| `EmptyState`, `ErrorState`, `LoadingSkeleton` | وضعیت‌های استاندارد |
| `ConfirmDialog` | تأیید عملیات برگشت‌ناپذیر (دکمه خطر قرمز) |
| `JalaliDatePicker` / `DatePickerDialog` | تقویم ماهانه شمسی/میلادی، شروع هفته از تنظیمات |
| `PeriodSelector` | این ماه / ماه قبل / امسال / سفارشی |
| `Donut`, `TrendLine`, `BarCompare` | نمودارها (Canvas / Vico) + خلاصه متنی برای TalkBack |
| `EstimateBadge` | برچسب «برآورد» برای اعداد پیش‌بینی‌شده (خط‌چین در نمودار) |

## ۶.۶ الگوهای تعامل
- FAB ثبت سریع روی خانه و تراکنش‌ها؛ فرم در صفحه کامل (نه dialog).
- حذف: swipe یا منو → حذف نرم + Snackbar «بازگردانی».
- انتخابگرها (حساب، دسته، ارز، فیلتر): Modal bottom sheet.
- عملیات برگشت‌ناپذیر: ConfirmDialog؛ عملیات خیلی خطرناک (حذف همه داده/بازیابی): تایپ عبارت تأیید + احراز هویت.
- بازخورد لمسی (haptic) سبک هنگام ذخیره موفق.
- اعداد پیش‌بینی همیشه با `EstimateBadge` و خط‌چین.

## ۶.۷ RTL/LTR
- `supportsRtl=true`؛ Compose جهت را از Locale می‌گیرد.
- آیکون‌های جهت‌دار (برگشت، فلش) نسخه AutoMirrored.
- مبالغ، کدهای ارز، شماره کارت: LTR isolation.
- محور زمانی نمودارها: از قدیم به جدید، هم‌جهت با جهت خواندن (RTL: راست به چپ) — با برچسب صریح.

## ۶.۸ دسترس‌پذیری
TalkBack: هر ردیف تراکنش یک توضیح کامل (نوع، مبلغ، دسته، تاریخ). نمودارها خلاصه متنی. پشتیبانی font scale تا ۲۰۰٪ (طراحی بدون ارتفاع ثابت متن). ترتیب فوکوس منطقی. هدف لمسی ≥ ۴۸dp.

## ۶.۹ برندینگ قابل تغییر
همه موارد برند در: `core/designsystem/.../theme/BrandPalette.kt` (رنگ‌ها)، `app/src/main/res/values/brand.xml` و `values-fa/brand.xml` (نام برنامه)، `app/src/main/res/drawable/ic_launcher_foreground.xml` (لوگو)، `res/font/` (فونت). `applicationId` (`com.mymoney.app`) پس از انتشار عمومی قابل تغییر نیست.
