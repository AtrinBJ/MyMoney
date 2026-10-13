# ۳. مدل داده

## ۳.۱ قراردادهای کلی
| موضوع | قرارداد |
|---|---|
| شناسه | `TEXT` با قالب UUIDv7 (۳۶ کاراکتر). پایدار است، در پشتیبان، بازیابی و همگام‌سازی آینده تغییر نمی‌کند و بر اساس زمان مرتب می‌شود. استثناها: `currency.code` و جدول‌های اتصال که PK ترکیبی دارند. |
| مبلغ پولی | `INTEGER` بر حسب **کوچک‌ترین واحد** ارز (`amountMinor`). تعداد رقم اعشار از `currency.minorDigits` خوانده می‌شود: EUR=2 (سنت)، IRR=0 (ریال)، JPY=0. |
| سقف مبلغ | قدرمطلق هر مبلغ ≤ 10^15 واحد خرد است (مثلاً ۱۰۰ هزار میلیارد تومان). با این سقف، جمع ۹۲۰۰ تراکنش حداکثری هم از `Long` سرریز نمی‌کند. همه جمع‌ها در Kotlin با `Math.addExact` انجام می‌شوند. |
| عدد اعشاری غیرپولی | `TEXT` به شکل رشته متعارف `BigDecimal` (`toPlainString`، بدون صفرهای اضافه). کاربرد: نرخ ارز، مقدار دارایی، قیمت واحد، نرخ بهره. |
| تاریخ تقویمی | `INTEGER` برابر `LocalDate.toEpochDay()`. به منطقه زمانی وابسته نیست. |
| ساعت | `INTEGER` دقیقه از ابتدای روز (0..1439)، اختیاری |
| لحظه | `INTEGER` میلی‌ثانیه از epoch به وقت UTC (`createdAt`، `updatedAt`، `deletedAt`) |
| بولی | `INTEGER` با مقدار 0 یا 1 |
| enum | `TEXT` برابر نام ثابت enum. **تغییر نام ثابت‌های enum ممنوع است**، چون در پشتیبان‌ها ذخیره می‌شوند. |
| حذف | تراکنش‌ها حذف نرم می‌شوند (`deletedAt`). حساب، دسته و دارایی به‌جای حذف بایگانی می‌شوند (`isArchived`). حذف سخت فقط برای رکوردهای بدون وابستگی، یا با تأیید صریح و به‌صورت آبشاری کنترل‌شده انجام می‌شود. |
| رنگ و آیکون | `colorKey` و `iconKey` به‌صورت `TEXT` که به توکن‌های Design System اشاره می‌کنند. رنگ خام ذخیره نمی‌شود تا در تم روشن و تاریک درست تطبیق پیدا کند. |

## ۳.۲ نسخه‌های schema
| نسخه | مرحله | جدول‌های افزوده‌شده |
|---|---|---|
| 1 | هسته مالی | `currency`، `exchange_rate`، `account`، `category`، `tag`، `entry`، `entry_split`، `entry_tag`، `change_log` + view با نام `v_category_amount` |
| 2 | امکانات پیشرفته | `budget`، `budget_category`، `budget_period_override`، `budget_alert_state`، `recurring_rule`، `recurring_amount_change`، `occurrence_state`، `obligation`، `asset`، `asset_trade`، `asset_valuation`، `savings_goal`، `goal_account`، `goal_contribution`، `notification_log` |
| 3 | امنیت و تبادل | `attachment`، `import_batch`، `backup_record` |

**سیاست مهاجرت:**
- `exportSchema = true` است و JSON هر نسخه در `core/data/schemas/` در git نگه داشته می‌شود.
- هر افزایش نسخه یک `Migration` صریح دارد. افزودن جدول از طریق SQL که Room تولید می‌کند انجام می‌شود.
- هر مهاجرت تست `MigrationTestHelper` دارد: داده نسخه قبل درج می‌شود، مهاجرت اجرا می‌شود و صحت داده و schema بررسی می‌شود.
- `fallbackToDestructiveMigration` **هرگز** استفاده نمی‌شود.
- اگر پشتیبانی از نسخه قدیمی‌تر باشد، `BackupReader` آن را با mapperهای نسخه‌دار به قالب فعلی ارتقا می‌دهد.

---

## ۳.۳ جدول‌های نسخه ۱

### `currency`
| فیلد | نوع | قید / پیش‌فرض |
|---|---|---|
| code | TEXT **PK** | ۳ تا ۸ کاراکتر `[A-Z0-9]`. ارزهای استاندارد کد ISO 4217 دارند و کد سفارشی هم مجاز است. |
| minorDigits | INTEGER NOT NULL | 0..4. **پس از استفاده در یک حساب یا تراکنش قابل تغییر نیست.** |
| symbol | TEXT NOT NULL | |
| nameFa | TEXT NOT NULL | |
| nameEn | TEXT NOT NULL | |
| isCustom | INTEGER NOT NULL | پیش‌فرض 0 |
| isEnabled | INTEGER NOT NULL | پیش‌فرض 1. فقط ارزهای فعال در انتخابگرها نمایش داده می‌شوند. |
| sortOrder | INTEGER NOT NULL | پیش‌فرض 0 |

- **مقادیر اولیه:** IRR(0)، USD(2)، EUR(2)، GBP(2)، AED(2)، TRY(2)، CAD(2)، AUD(2)، CHF(2)، SEK(2)، NOK(2)، DKK(2)، JPY(0)، CNY(2)، INR(2)
- **حذف:** فقط ارز سفارشی‌ای که در هیچ جا استفاده نشده باشد حذف می‌شود. ارزهای دیگر فقط غیرفعال می‌شوند.
- **تومان:** ارز جداگانه نیست. توضیح در قاعده FBR-22 آمده است.

### `exchange_rate`
| فیلد | نوع | قید |
|---|---|---|
| id | TEXT PK | |
| baseCode | TEXT NOT NULL | FK به `currency(code)`، RESTRICT |
| quoteCode | TEXT NOT NULL | FK به `currency(code)`، RESTRICT؛ باید با baseCode متفاوت باشد |
| rate | TEXT NOT NULL | BigDecimal > 0 با حداکثر ۱۸ رقم معنادار. معنا: ۱ واحد base برابر rate واحد quote |
| date | INTEGER NOT NULL | epochDay |
| source | TEXT NOT NULL | `MANUAL`، `TRANSFER_IMPLIED` یا `IMPORT` (مقادیر آینده: نام API) |
| note | TEXT | |
| createdAt | INTEGER NOT NULL | |

- **ایندکس‌ها:** `UNIQUE(baseCode, quoteCode, date, source)` و `INDEX(baseCode, quoteCode, date)`
- **حذف:** حذف سخت آزاد است. گزارش‌ها نرخ را در زمان محاسبه دوباره جست‌وجو می‌کنند و چیزی snapshot نمی‌شود.

### `account`
| فیلد | نوع | قید / پیش‌فرض |
|---|---|---|
| id | TEXT PK | |
| name | TEXT NOT NULL | ۱ تا ۶۰ کاراکتر، trim شده |
| type | TEXT NOT NULL | `CASH`، `BANK`، `SAVINGS`، `CREDIT_CARD`، `INVESTMENT`، `DIGITAL_WALLET`، `OTHER`، `LIABILITY`، `RECEIVABLE` |
| currencyCode | TEXT NOT NULL | FK به currency، RESTRICT. **اگر حساب تراکنش داشته باشد قابل تغییر نیست.** |
| institution | TEXT | |
| startDate | INTEGER NOT NULL | epochDay |
| note | TEXT | |
| colorKey | TEXT | |
| iconKey | TEXT | |
| isArchived | INTEGER NOT NULL | پیش‌فرض 0 |
| includeInNetWorth | INTEGER NOT NULL | پیش‌فرض 1 |
| includeInLiquid | INTEGER NOT NULL | پیش‌فرض بر اساس نوع حساب: CASH، BANK و DIGITAL_WALLET مقدار 1 و بقیه 0 |
| creditLimitMinor | INTEGER | فقط برای CREDIT_CARD |
| sortOrder | INTEGER NOT NULL | پیش‌فرض 0 |
| createdAt, updatedAt | INTEGER NOT NULL | |

- **ایندکس:** `INDEX(isArchived, sortOrder)`
- **موجودی ذخیره نمی‌شود.** موجودی اولیه یک `entry` از نوع `OPENING_BALANCE` با تاریخ `startDate` است.
- انواع `LIABILITY` و `RECEIVABLE` حساب سیستمی بدهی و طلب هستند. از نسخه ۲ برای هرکدام یک ردیف `obligation` هم وجود دارد.
- **سیاست حذف:**
  - حسابی که entry دارد فقط بایگانی می‌شود.
  - «حذف حساب و همه تراکنش‌هایش» یک عملیات صریح با تأیید دومرحله‌ای است. این عملیات legهای مقابلِ انتقال‌ها را هم حذف می‌کند و پیش از اجرا هشدار می‌دهد.

### `category`
| فیلد | نوع | قید |
|---|---|---|
| id | TEXT PK | |
| type | TEXT NOT NULL | `INCOME` یا `EXPENSE` |
| parentId | TEXT | FK به `category(id)`، RESTRICT. **فقط یک سطح عمق مجاز است** و نوع والد و فرزند باید یکی باشد. |
| systemKey | TEXT UNIQUE | مثلاً `expense.food`. فقط دسته‌های پیش‌فرض این کلید را دارند. |
| name | TEXT | نام سفارشی. اگر NULL باشد و systemKey داشته باشد، نام ترجمه‌شده نمایش داده می‌شود. |
| iconKey, colorKey | TEXT | |
| defaultCostType | TEXT | `FIXED` یا `VARIABLE` یا NULL؛ پیش‌فرض فرم |
| defaultEssential | INTEGER | NULL یا 0 یا 1 |
| isArchived | INTEGER NOT NULL | پیش‌فرض 0 |
| sortOrder | INTEGER NOT NULL | |
| createdAt, updatedAt | INTEGER NOT NULL | |

- **ایندکس‌ها:** `INDEX(type, isArchived, sortOrder)`، `INDEX(parentId)` و `UNIQUE(systemKey)`
- **دسته‌های محافظت‌شده** (قابل تغییر نام ولی غیرقابل حذف یا بایگانی): `expense.fees` (کارمزد)، `expense.interest` (بهره و کارمزد وام)، `income.other` و `expense.other`
- **حذف:**
  - دسته‌ای که استفاده شده فقط بایگانی می‌شود، یا «انتقال تراکنش‌ها به دسته دیگر و سپس حذف» انجام می‌شود.
  - دسته‌ای که زیردسته دارد قابل حذف نیست.
- **پایداری تاریخچه:** تراکنش‌ها با `id` به دسته ارجاع می‌دهند، پس تغییر نام دسته فقط نام نمایشی را عوض می‌کند و تاریخچه را ناسازگار نمی‌کند.

### `entry` (هسته دفتر کل؛ هر ردیف = یک حرکت روی یک حساب)
| فیلد | نوع | قید |
|---|---|---|
| id | TEXT PK | |
| groupId | TEXT NOT NULL | شناسه عملیات. legهای یک انتقال یا یک پرداخت ترکیبی groupId مشترک دارند. **هنگام باز شدن فرم ساخته می‌شود** تا ثبت تکراری ممکن نباشد. |
| accountId | TEXT NOT NULL | FK به `account(id)`، RESTRICT |
| kind | TEXT NOT NULL | `OPENING_BALANCE`، `INCOME`، `EXPENSE`، `REFUND`، `TRANSFER`، `ADJUSTMENT`، `ASSET_BUY`، `ASSET_SELL`، `WRITE_OFF` |
| amountMinor | INTEGER NOT NULL | علامت‌دار و به ارز حساب. مثبت یعنی پول وارد حساب شده. برای هر kind قید علامت دارد (جدول ۳.۵). |
| date | INTEGER NOT NULL | epochDay |
| timeMinutes | INTEGER | 0..1439 |
| status | TEXT NOT NULL | `COMPLETED` (پیش‌فرض) یا `PENDING` |
| categoryId | TEXT | FK به `category(id)`، RESTRICT. برای INCOME، EXPENSE و REFUND الزامی است، مگر اینکه split وجود داشته باشد. برای بقیه kindها NULL است. |
| payee | TEXT | فروشگاه، دریافت‌کننده یا پرداخت‌کننده |
| note | TEXT | |
| costType | TEXT | `FIXED`، `VARIABLE` یا NULL؛ فقط برای EXPENSE |
| essential | INTEGER | NULL، 0 یا 1؛ فقط برای EXPENSE |
| relatedEntryId | TEXT | FK به `entry(id)` با SET NULL. برای REFUND به هزینه اصلی اشاره می‌کند. |
| recurringRuleId | TEXT | ارجاع منطقی و بدون FK (جدول در v2 اضافه شده است). هنگام حذف rule در کد NULL می‌شود. |
| occurrenceDate | INTEGER | سررسیدی که این entry آن را پرداخته است |
| assetTradeId | TEXT | ارجاع منطقی (v2) |
| importBatchId | TEXT | ارجاع منطقی (v3) |
| createdAt, updatedAt | INTEGER NOT NULL | |
| deletedAt | INTEGER | NULL یعنی رکورد فعال است |

**ایندکس‌ها:**
- `INDEX(accountId, deletedAt, date)` برای موجودی و فهرست تراکنش‌های یک حساب
- `INDEX(date)` برای گزارش‌های بازه‌ای
- `INDEX(categoryId, date)`
- `INDEX(groupId)`
- `INDEX(relatedEntryId)`
- `INDEX(recurringRuleId, occurrenceDate)`
- `INDEX(importBatchId)`

**قواعد اعتبارسنجی (در domain، قبل از ذخیره):**
- `amountMinor ≠ 0` و `|amountMinor| ≤ 10^15`. قید علامت بر اساس kind اعمال می‌شود.
- تاریخ باید در بازه ۱۹۰۰-۰۱-۰۱ تا ۲۲۰۰-۱۲-۳۱ باشد. تاریخ آینده مجاز است ولی در فرم هشدار نمایش داده می‌شود.
- هر حساب حداکثر یک `OPENING_BALANCE` فعال دارد.
- گروه TRANSFER دقیقاً یک leg منفی و یک leg مثبت از نوع TRANSFER روی دو حساب **متفاوت** دارد، به‌اضافه حداکثر یک leg کارمزد از نوع EXPENSE با دسته `expense.fees`.
- مبلغ REFUND نباید از «مبلغ قابل برگشت» هزینه اصلی بیشتر شود. «مبلغ قابل برگشت» یعنی |مبلغ اصلی| منهای جمع برگشت‌های قبلی، که در ارز حساب اصلی محاسبه می‌شود.
- `deletedAt` فقط برای کل یک group تنظیم می‌شود. حذف یک leg به‌تنهایی ممنوع است.

**حذف:** نرم و در سطح group انجام می‌شود، با Undo در snackbar. پس از ۳۰ روز، `DailyMaintenanceWorker` آن را دائمی پاک می‌کند (split و tag به‌صورت CASCADE). صفحه «سطل زباله» امکان بازگردانی دارد.

### `entry_split`
| فیلد | نوع | قید |
|---|---|---|
| id | TEXT PK | |
| entryId | TEXT NOT NULL | FK به `entry(id)`، CASCADE |
| categoryId | TEXT NOT NULL | FK به `category(id)`، RESTRICT |
| amountMinor | INTEGER NOT NULL | مقدار مثبت به ارز entry |
| note | TEXT | |

- **ایندکس‌ها:** `INDEX(entryId)` و `INDEX(categoryId)`
- **قاعده:** اگر entry حداقل ۲ split داشته باشد، `entry.categoryId = NULL` و `Σ split.amountMinor = |entry.amountMinor|`. همه splitها از نوعِ (درآمد یا هزینه) entry هستند.

### `tag` و `entry_tag`
- `tag`: `id` (PK)، `name` با `TEXT NOT NULL COLLATE NOCASE UNIQUE` (۱ تا ۳۰ کاراکتر)، `colorKey` و `createdAt`
- `entry_tag`: PK برابر `(entryId, tagId)`. هر دو FK از نوع CASCADE هستند و `INDEX(tagId)` دارد.
- **حذف tag:** فقط پیوندها حذف می‌شوند و تراکنش‌ها دست نمی‌خورند.

### `change_log`
| فیلد | نوع | توضیح |
|---|---|---|
| id | TEXT PK | |
| entityType | TEXT NOT NULL | `ENTRY_GROUP`، `ACCOUNT`، `CATEGORY`، `OBLIGATION`، `ASSET`، `GOAL`، `BUDGET`، `RULE` |
| entityId | TEXT NOT NULL | |
| action | TEXT NOT NULL | `CREATE`، `UPDATE`، `DELETE`، `RESTORE`، `ARCHIVE`، `UNARCHIVE`، `ADJUST` |
| at | INTEGER NOT NULL | |
| reason | TEXT | برای ADJUST الزامی است |
| beforeJson, afterJson | TEXT | snapshot مختصر به JSON. داخل DB رمزشده نگهداری می‌شود. |

- **ایندکس:** `INDEX(entityType, entityId, at)`
- **نگهداری:** دائمی است و فقط با حذف کامل داده پاک می‌شود.

### View به نام `v_category_amount`
```sql
SELECT e.id AS entryId, e.groupId, e.accountId, e.kind, e.status, e.date,
       COALESCE(s.categoryId, e.categoryId) AS categoryId,
       CASE WHEN s.id IS NULL THEN e.amountMinor
            WHEN e.amountMinor < 0 THEN -s.amountMinor ELSE s.amountMinor END AS amountMinor,
       e.relatedEntryId
FROM entry e LEFT JOIN entry_split s ON s.entryId = e.id
WHERE e.deletedAt IS NULL
```
همه گزارش‌های دسته‌محور از این view استفاده می‌کنند، پس تقسیم‌ها همیشه یکسان شمرده می‌شوند.

---

## ۳.۴ جدول‌های نسخه ۲

### `budget`
| فیلد | نوع | قید |
|---|---|---|
| id | TEXT PK | |
| name | TEXT NOT NULL | |
| periodType | TEXT NOT NULL | `WEEKLY`، `MONTHLY`، `YEARLY` یا `CUSTOM` |
| calendarSystem | TEXT NOT NULL | `JALALI` یا `GREGORIAN`؛ مرز ماه و سال را تعیین می‌کند |
| startDate | INTEGER NOT NULL | ابتدای اولین دوره |
| endDate | INTEGER | برای CUSTOM الزامی است |
| amountMinor | INTEGER NOT NULL | > 0 |
| currencyCode | TEXT NOT NULL | FK به currency |
| scope | TEXT NOT NULL | `TOTAL` (همه دسته‌های هزینه) یا `CATEGORIES` |
| rolloverMode | TEXT NOT NULL | `NONE` (پیش‌فرض)، `CARRY_UNUSED` یا `CARRY_ALL` |
| alertThresholds | TEXT NOT NULL | مقدار پیش‌فرض `"50,80,100"`؛ اعداد صحیح ۱ تا ۲۰۰ |
| includePending | INTEGER NOT NULL | پیش‌فرض 0 |
| isArchived | INTEGER NOT NULL | پیش‌فرض 0 |
| createdAt, updatedAt | INTEGER NOT NULL | |

- `budget_category(budgetId FK CASCADE, categoryId FK CASCADE)` با PK ترکیبی. اگر دسته والد انتخاب شود، زیردسته‌هایش هم شامل می‌شوند.
- `budget_period_override(budgetId FK CASCADE, periodStart, amountMinor)` با PK برابر (budgetId, periodStart)؛ برای تغییر سقف فقط در یک دوره.
- `budget_alert_state(budgetId FK CASCADE, periodStart, lastThresholdPct, notifiedAt)` با PK برابر (budgetId, periodStart)؛ تا هر آستانه در هر دوره فقط یک بار اعلان شود.

### `recurring_rule`
| فیلد | نوع | قید |
|---|---|---|
| id | TEXT PK | |
| kind | TEXT NOT NULL | `BILL`، `SUBSCRIPTION`، `INCOME`، `LOAN_INSTALLMENT`، `TRANSFER` یا `OTHER` |
| name | TEXT NOT NULL | |
| accountId | TEXT NOT NULL | FK به account، RESTRICT؛ حساب پرداخت یا دریافت |
| toAccountId | TEXT | برای TRANSFER و LOAN_INSTALLMENT (حساب بدهی) |
| categoryId | TEXT | FK به category با SET NULL |
| amountMinor | INTEGER NOT NULL | مبلغ پایه، > 0. تغییرات از تاریخ مشخص در `recurring_amount_change` ثبت می‌شوند. |
| amountIsEstimate | INTEGER NOT NULL | مثلاً قبض متغیر |
| frequency | TEXT NOT NULL | `ONCE`، `DAILY`، `WEEKLY`، `MONTHLY` یا `YEARLY` |
| interval | INTEGER NOT NULL | ≥ 1؛ «دوره سفارشی» یعنی هر N روز، هفته، ماه یا سال |
| calendarSystem | TEXT NOT NULL | |
| startDate | INTEGER NOT NULL | اولین سررسید |
| anchorDay | INTEGER | روز ماه، 1..31، برای MONTHLY و YEARLY |
| endDate | INTEGER | |
| maxOccurrences | INTEGER | |
| postingPolicy | TEXT NOT NULL | `MANUAL_CONFIRM` (پیش‌فرض) یا `AUTO_POST` |
| reminderDaysBefore | INTEGER | NULL یعنی بدون اعلان |
| status | TEXT NOT NULL | `ACTIVE`، `PAUSED` یا `ENDED` |
| payee, note | TEXT | |
| splitInterest | INTEGER NOT NULL | برای قسط وام: پیشنهاد تفکیک اصل از بهره |
| createdAt, updatedAt | INTEGER NOT NULL | |

- `recurring_amount_change(id, ruleId FK CASCADE, effectiveFrom, amountMinor)` با `UNIQUE(ruleId, effectiveFrom)`. **تاریخچه دست نمی‌خورد**، چون پرداخت‌های گذشته entry واقعی هستند.
- `occurrence_state(ruleId FK CASCADE, dueDate, status, expectedMinor, updatedAt)` با PK برابر **(ruleId, dueDate)**. وضعیت یکی از `PARTIAL`، `PAID` یا `SKIPPED` است. نبودِ ردیف یعنی سررسید هنوز «باز» است. این PK از ثبت دوباره ثبت خودکار جلوگیری می‌کند (FBR-12).

### `obligation` (جزئیات بدهی و طلب)
| فیلد | نوع | قید |
|---|---|---|
| accountId | TEXT **PK** | FK به `account(id)`، CASCADE. نوع حساب باید LIABILITY یا RECEIVABLE باشد. |
| direction | TEXT NOT NULL | `PAYABLE` (بدهی من) یا `RECEIVABLE` (طلب من) |
| counterparty | TEXT NOT NULL | نام طرف حساب |
| kind | TEXT NOT NULL | `BANK_LOAN`، `PERSONAL`، `INSTALLMENT_PURCHASE`، `FAMILY`، `CUSTOMER` یا `OTHER` |
| principalMinor | INTEGER NOT NULL | مبلغ اولیه، > 0 |
| startDate | INTEGER NOT NULL | |
| dueDate | INTEGER | سررسید نهایی |
| annualRate | TEXT | BigDecimal درصد، ≥ 0 |
| interestMethod | TEXT NOT NULL | `NONE`، `SIMPLE` یا `ANNUITY` |
| termMonths | INTEGER | |
| status | TEXT NOT NULL | `OPEN`، `SETTLED` یا `WRITTEN_OFF` |
| note | TEXT | |
| createdAt, updatedAt | INTEGER NOT NULL | |

- **مانده** = منفیِ موجودی حساب برای PAYABLE، و خود موجودی برای RECEIVABLE. مانده ذخیره نمی‌شود.

### `asset`، `asset_trade` و `asset_valuation`
- **`asset`:**
  - فیلدها: `id`، `name`، `type`، `unit` (مثلاً `GRAM`، `MITHQAL`، `COIN`، `SHARE`، `UNIT` یا مقدار آزاد)، `currencyCode` (ارز ارزش‌گذاری)، `valuationMode` (`PER_UNIT` یا `TOTAL`)، `includeInNetWorth`، `isArchived`، `note`، `createdAt` و `updatedAt`
  - type یکی از `GOLD`، `STOCK`، `FUND`، `CRYPTO`، `VEHICLE`، `REAL_ESTATE`، `PERSONAL` یا `OTHER` است. **نوع «نقد یا حساب» وجود ندارد** (FBR-10).
- **`asset_trade`:**
  - فیلدها: `id`، `assetId` (FK با RESTRICT)، `date`، `type` (`OPENING`، `BUY`، `SELL` یا `ADJUST`)، `quantity` (TEXT > 0)، `grossMinor` (≥ 0، به ارز دارایی)، `feesMinor` (≥ 0)، `entryGroupId`، `note`، `createdAt`، `updatedAt` و `deletedAt`
  - **ایندکس:** `INDEX(assetId, date)`
- **`asset_valuation`:**
  - فیلدها: `id`، `assetId` (FK با CASCADE)، `date`، `unitPrice` (TEXT)، `totalValueMinor`، `currencyCode`، `source` (`MANUAL`، `TRADE` یا `ESTIMATE`)، `note` و `createdAt`
  - **ایندکس:** `INDEX(assetId, date)`
  - **قاعده:** دقیقاً یکی از `unitPrice` و `totalValueMinor` مقدار دارد.

### `savings_goal`، `goal_account` و `goal_contribution`
- **`savings_goal`:**
  - فیلدها: `id`، `name`، `targetMinor` (> 0)، `currencyCode`، `targetDate`، `priority` (1 = بالا، 2 یا 3)، `mode` (`ALLOCATION` یا `LINKED_ACCOUNTS`)، `status` (`ACTIVE`، `ACHIEVED` یا `ARCHIVED`)، `iconKey`، `colorKey`، `note`، `createdAt` و `updatedAt`
- **`goal_account`:** فیلدهای `goalId` و `accountId`، هر دو با CASCADE، و PK ترکیبی.
- **`goal_contribution`:**
  - فیلدها: `id`، `goalId` (FK با CASCADE)، `accountId` (FK با SET NULL)، `date`، `amountMinor` (علامت‌دار: مثبت یعنی تخصیص و منفی یعنی آزادسازی)، `note` و `createdAt`
  - **ایندکس:** `INDEX(goalId, date)`

### `notification_log`
- فیلدها: `key` با `TEXT PK`، مثلاً `due:{ruleId}:{date}:{daysBefore}` یا `budget:{id}:{period}:{pct}`، و `sentAt`.
- رکوردهای قدیمی‌تر از ۱۸۰ روز پاک می‌شوند.

## ۳.۵ جدول‌های نسخه ۳
- **`attachment`:**
  - فیلدها: `id`، `groupId` (ارجاع منطقی به عملیات)، `storedName` (نام تصادفی فایل رمزشده در `filesDir/attachments`)، `displayName`، `mimeType`، `sizeBytes`، `sha256` و `createdAt`
  - **ایندکس:** `INDEX(groupId)`
  - **تغییر نسبت به طرح اولیه (مرحله ۵):** پیوست به عملیات (`groupId`) وصل است، نه به یک leg (`entryId`)، تا ویرایش عملیات پیوست را یتیم نکند.
  - **حذف فایل فیزیکی:** هنگام پاک‌سازی دائمی entry انجام می‌شود.
- **`import_batch`:**
  - فیلدها: `id`، `fileName`، `fileSha256`، `importedAt`، `rowCount`، `importedCount`، `skippedCount`، `accountId` و `status` (`COMPLETED` یا `REVERTED`)
  - **ایندکس:** `INDEX(fileSha256)`
- **`backup_record`:** فیلدهای `id`، `createdAt`، `fileName`، `sizeBytes`، `encrypted`، `formatVersion`، `entryCount` و `kind` (`MANUAL` یا `SAFETY`).

## ۳.۶ قید علامت و دسته بر اساس kind
| kind | علامت amount | categoryId | حساب مجاز |
|---|---|---|---|
| OPENING_BALANCE | هر علامت (≠ 0) | NULL | همه |
| INCOME | > 0 | INCOME، الزامی (یا split) | همه |
| EXPENSE | < 0 | EXPENSE، الزامی (یا split) | همه |
| REFUND | > 0 | EXPENSE، همان دسته هزینه اصلی | همه |
| TRANSFER | ≠ 0 | NULL | همه؛ دو leg روی دو حساب متفاوت |
| ADJUSTMENT | ≠ 0 | NULL | همه؛ reason در change_log الزامی است |
| ASSET_BUY | < 0 | NULL | غیر از RECEIVABLE |
| ASSET_SELL | > 0 | NULL | غیر از LIABILITY |
| WRITE_OFF | ≠ 0 | NULL | فقط LIABILITY و RECEIVABLE |

## ۳.۷ نگاشت با فهرست موجودیت‌های درخواست
| درخواست | این طراحی | دلیل تفاوت |
|---|---|---|
| Transaction | `entry` | هر ردیف یک leg است، پس موجودی همیشه برابر `SUM` است. |
| TransactionSplit | `entry_split` | — |
| Transfer | دو `entry` با `groupId` مشترک | جدول جدا یک منبع حقیقت دوم می‌ساخت. |
| Budget / BudgetPeriod | `budget` + `budget_period_override` + `budget_alert_state` | دوره‌ها محاسبه می‌شوند و فقط استثناها ذخیره می‌شوند. |
| RecurringRule / ScheduledPayment | `recurring_rule` + `occurrence_state` | سررسیدها از rule محاسبه می‌شوند و فقط وضعیت‌ها ذخیره می‌شوند. |
| Debt / Receivable | حساب `LIABILITY` یا `RECEIVABLE` + `obligation` | اصل بدهی یا طلب به‌صورت ساختاری درآمد یا هزینه نمی‌شود. |
| DebtPayment / ReceivablePayment | TRANSFER (و EXPENSE بهره با دسته `expense.interest`) | — |
| Asset / AssetValuation | `asset` + `asset_trade` + `asset_valuation` | trade برای بهای تمام‌شده و سود و زیان لازم است. |
| SavingsGoal / GoalContribution | `savings_goal` + `goal_account` + `goal_contribution` | — |
| Subscription | `recurring_rule` با `kind=SUBSCRIPTION` | یک سازوکار واحد برای همه پرداخت‌های دوره‌ای |
| Currency / ExchangeRate | `currency` / `exchange_rate` | — |
| Attachment | `attachment` | — |
| Reminder | فیلدهای rule (`reminderDaysBefore`) + `notification_log` | یادآوری از داده اصلی مشتق می‌شود. |
| AppSettings | DataStore (`settings.preferences_pb`) | تنظیمات رابط کاربری جدا از داده مالی است و در پشتیبان گنجانده می‌شود. |
| BackupMetadata | `backup_record` + `manifest.json` داخل فایل پشتیبان | — |
| Tag / TransactionTag | `tag` / `entry_tag` | — |

## ۳.۸ تنظیمات (DataStore)
| کلید | پیش‌فرض |
|---|---|
| `onboardingDone` | false |
| `language` | `fa` یا `en` (پیش‌فرض: زبان سیستم، در غیر این صورت `fa`) |
| `calendarSystem` | `JALALI` برای fa و `GREGORIAN` برای en |
| `digitStyle` | `PERSIAN` برای fa و `LATIN` برای en |
| `firstDayOfWeek` | `SATURDAY` برای fa و `MONDAY` برای en |
| `baseCurrency` | `IRR` |
| `irrDisplayUnit` | `TOMAN` |
| `themeMode` | `SYSTEM` |
| `dynamicColor` | false |
| `hideAmounts`، `hideAmountsOnStart` | false |
| `secureScreen` | true |
| `lockEnabled` | false |
| `biometricEnabled` | false |
| `autoLockMinutes` | 1 |
| `remindersEnabled` | false |
| `reminderHour` | 9 |
| `dashboardHiddenCards` | ∅ |
| `lastBackupAt` | — |
| `backupReminderDays` | 30 |
| `pinHashBlob`، `pinFailCount`، `pinLockedUntil` | — (`pinHashBlob` رمزشده است) |

## ۳.۹ کارایی
- **جمع‌ها در SQL:**
  - `SUM(amountMinor) GROUP BY accountId` برای موجودی همه حساب‌ها در یک کوئری
  - `GROUP BY categoryId` روی `v_category_amount` برای گزارش‌ها
- **فهرست تراکنش‌ها:** Paging 3 با `PagingSource` از Room و اندازه صفحه ۵۰.
- **جست‌وجوی متن:** `LIKE` روی `payee` و `note` با محدودیت و صفحه‌بندی. اگر محک عملکرد نیاز را نشان دهد، FTS4 بعداً اضافه می‌شود.
