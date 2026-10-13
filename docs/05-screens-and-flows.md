# ۵. نقشه صفحات و مسیرهای کاربری

## ۵.۱ ساختار ناوبری
```
LockGate ──(قفل)──► LockScreen (PIN / بیومتریک)
   │
   ├─(اولین اجرا)─► Onboarding: Language → Currency&Unit → Calendar&Digits → FirstAccount → Security → Done
   │
   └─► MainScaffold  [Bottom bar: خانه · تراکنش‌ها · برنامه‌ریزی · دارایی‌ها · گزارش‌ها]   [FAB: ثبت سریع]
        │  TopBar: جست‌وجوی سراسری 🔍 · مخفی‌سازی مبالغ 👁 · تنظیمات ⚙
        ├─ Home (Dashboard)
        │    ├─ PeriodPicker (این ماه / ماه قبل / امسال / بازه دلخواه)
        │    ├─ Alerts → AlertDetail (لینک به منبع)
        │    └─ کارت‌ها → صفحه مقصد هر کارت
        ├─ Transactions
        │    ├─ List (Paging، گروه‌بندی روز، خلاصه درآمد/هزینه بازه)
        │    ├─ FilterSheet (تاریخ، حساب، دسته، برچسب، نوع، وضعیت، مبلغ از/تا)
        │    ├─ EntryDetail → Edit / Copy / Delete(Undo) / Refund
        │    ├─ EntryEditor (Expense | Income | Transfer | + Refund | Adjustment)
        │    │     └─ SplitEditor · TagPicker · CategoryPicker · AccountPicker · JalaliDatePicker · AttachmentPicker
        │    └─ Trash (بازگردانی/حذف دائمی)
        ├─ Planning
        │    ├─ Budgets → BudgetDetail (دوره‌ها، روند) → BudgetEditor
        │    ├─ Bills & Recurring (List | Calendar) → RuleDetail (سررسیدها، تاریخچه) → RuleEditor
        │    │     └─ OccurrenceAction (پرداخت کامل / جزئی / رد / تعویق)
        │    ├─ Subscriptions (جمع ماهانه/سالانه، تمدیدهای نزدیک)
        │    ├─ Goals → GoalDetail → GoalEditor · Allocate/Release
        │    └─ Tools: LoanCalculator · SavingsRate · AverageSpending · CutScenario
        ├─ Wealth
        │    ├─ NetWorthSummary (دارایی / بدهی / خالص + روند)
        │    ├─ Accounts → AccountDetail (موجودی، تاریخچه) → AccountEditor · Transfer · Adjust · Archive
        │    ├─ Assets → AssetDetail (مقدار، بهای تمام‌شده، ارزش، سود/زیان) → Buy · Sell · Revalue · AssetEditor
        │    └─ Debts & Receivables [تب‌ها] → ObligationDetail (مانده، برنامه، تاریخچه) → Pay/Collect · WriteOff · Editor
        └─ Reports
             ├─ ReportCatalog (۱۳ گزارش)
             ├─ ReportScreen (فیلتر بازه/حساب/دسته، نمودار، جدول قابل مرتب‌سازی، درصد تغییر، Export CSV/PDF)
             └─ Insights (بینش‌ها با توضیح «چرا؟»)

Settings
 ├─ General: زبان، تقویم، ارقام، شروع هفته، تم، رنگ پویا
 ├─ Currency: ارز پایه، واحد ریال/تومان، ارزها، نرخ‌های ارز (List → RateEditor)
 ├─ Categories & Tags
 ├─ Dashboard cards (نمایش/پنهان)
 ├─ Security: PIN، بیومتریک، قفل خودکار، جلوگیری از اسکرین‌شات، شروع با مبالغ مخفی
 ├─ Notifications: فعال، ساعت، انواع
 ├─ Data: Backup · Restore · Export CSV/JSON · Import CSV (Pick → Mapping → Preview → Confirm → Result) · Data health · Delete all data
 ├─ Help & guide
 └─ About & licenses
```

## ۵.۲ مسیرهای کلیدی
### ثبت سریع هزینه (هدف: ≤ ۵ ثانیه)
FAB → EntryEditor (نوع پیش‌فرض هزینه، حساب آخر استفاده‌شده، تاریخ امروز) → مبلغ (کیبورد عددی باز) → دسته (آخرین‌های پراستفاده بالای فهرست) → ذخیره. هشدار تکراری احتمالی پیش از ذخیره (FBR-36).

### انتقال بین دو ارز
EntryEditor[Transfer] → از حساب (EUR) / به حساب (IRR) → «مبلغ ارسالی» و «مبلغ دریافتی» (یکی از نرخ محاسبه می‌شود؛ نرخ ضمنی نمایش داده می‌شود) → کارمزد (اختیاری، حساب کسر) → ذخیره (۲ یا ۳ leg اتمی).

### پرداخت قسط وام
Planning › Bills یا Wealth › Debt › «پرداخت» → مبلغ کل، پیشنهاد تفکیک اصل/بهره (FBR-34، قابل ویرایش) → حساب پرداخت → ذخیره (TRANSFER اصل + EXPENSE بهره + occurrence_state).

### خرید طلا
Wealth › Assets › طلا › «خرید» → مقدار (گرم)، مبلغ کل، کارمزد، حساب پرداخت → ذخیره (ASSET_BUY + trade + ارزش‌گذاری TRADE).

### بازیابی پشتیبان
Settings › Data › Restore → انتخاب فایل (SAF) → (رمز در صورت رمزدار بودن) → بررسی سازگاری → خلاصه: «فایل: ۱۲ حساب، ۴٬۳۱۰ تراکنش، بازه ۱۴۰۱/۰۱–۱۴۰۵/۰۷ | داده فعلی: …» → هشدار جایگزینی → احراز هویت → پشتیبان ایمنی خودکار → بازیابی → گزارش نتیجه.

## ۵.۳ وضعیت‌های استاندارد هر صفحه
| وضعیت | الگو |
|---|---|
| Loading | Skeleton هم‌شکل محتوا (نه spinner تمام‌صفحه) |
| Empty | تصویر ساده + توضیح + دکمه اقدام اصلی («اولین حساب را بسازید») |
| Error | پیام قابل فهم + «تلاش دوباره»؛ جزئیات فنی فقط در debug |
| Success | Snackbar کوتاه (+ Undo برای حذف) |
| Partial data | بنر اطلاع (مثلاً «نرخ ارز برای ۲ تراکنش موجود نیست» + لینک) |

## ۵.۴ کارت‌های داشبورد (قابل پنهان‌سازی)
۱. ارزش خالص (دارایی/بدهی) ۲. نقدینگی قابل استفاده ۳. درآمد/هزینه/جریان خالص ماه + مقایسه با ماه قبل ۴. مصرف بودجه (کل + ۳ دسته پرمصرف) ۵. پرداخت‌های ۱۴ روز آینده و سررسیدگذشته ۶. اهداف (۳ هدف اول) ۷. آخرین تراکنش‌ها (۵) ۸. هشدارها و بینش‌های مهم ۹. پیش‌بینی پایان ماه (برچسب «برآورد») ۱۰. خلاصه ماه (نرخ پس‌انداز، میانگین هزینه روزانه)
