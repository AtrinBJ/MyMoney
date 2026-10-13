package com.mymoney.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.model.AccountType

/** Icon keys stored in the DB (category.iconKey / account.iconKey) mapped to Material icons. */
object IconKeys {
    private val map: Map<String, ImageVector> = mapOf(
        "home" to Icons.Filled.Home,
        "grocery" to Icons.Filled.LocalGroceryStore,
        "bus" to Icons.Filled.DirectionsBus,
        "bolt" to Icons.Filled.Bolt,
        "wifi" to Icons.Filled.Wifi,
        "bag" to Icons.Filled.ShoppingBag,
        "checkroom" to Icons.Filled.Checkroom,
        "health" to Icons.Filled.MedicalServices,
        "sports" to Icons.Filled.SportsSoccer,
        "school" to Icons.Filled.School,
        "flight" to Icons.Filled.Flight,
        "subscriptions" to Icons.Filled.Subscriptions,
        "car" to Icons.Filled.DirectionsCar,
        "shield" to Icons.Filled.Shield,
        "percent" to Icons.Filled.Percent,
        "gift" to Icons.Filled.CardGiftcard,
        "work" to Icons.Filled.Work,
        "bank" to Icons.Filled.AccountBalance,
        "more" to Icons.Filled.MoreHoriz,
        "payments" to Icons.Filled.Payments,
        "store" to Icons.Filled.Storefront,
        "laptop" to Icons.Filled.Laptop,
        "trophy" to Icons.Filled.EmojiEvents,
        "apartment" to Icons.Filled.Apartment,
        "trending" to Icons.AutoMirrored.Filled.TrendingUp,
        "savings" to Icons.Filled.Savings,
        "wallet" to Icons.Filled.Wallet,
        "card" to Icons.Filled.CreditCard,
        "chart" to Icons.Filled.ShowChart,
        "handshake" to Icons.Filled.Handshake,
        "quote" to Icons.Filled.RequestQuote,
        "transfer" to Icons.Filled.SwapHoriz,
        "adjust" to Icons.Filled.Tune,
        "category" to Icons.Filled.Category,
    )

    /** Keys offered in the category/account editors. */
    val selectable: List<String> = map.keys.filterNot { it in setOf("transfer", "adjust") }

    fun icon(key: String?): ImageVector = map[key] ?: Icons.Filled.Category

    fun forAccountType(type: AccountType): ImageVector = when (type) {
        AccountType.CASH -> Icons.Filled.Payments
        AccountType.BANK -> Icons.Filled.AccountBalance
        AccountType.SAVINGS -> Icons.Filled.Savings
        AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
        AccountType.INVESTMENT -> Icons.Filled.ShowChart
        AccountType.DIGITAL_WALLET -> Icons.Filled.AccountBalanceWallet
        AccountType.OTHER -> Icons.Filled.Wallet
        AccountType.LIABILITY -> Icons.Filled.RequestQuote
        AccountType.RECEIVABLE -> Icons.Filled.Handshake
    }
}

/** Color keys "c0".."c9" index the categorical chart palette so they adapt to light/dark themes. */
object ColorKeys {
    val all: List<String> = (0..9).map { "c$it" }

    @Composable
    fun color(key: String?): Color {
        val palette = MyMoneyTheme.finance.chart
        val index = key?.removePrefix("c")?.toIntOrNull()?.coerceIn(0, palette.lastIndex) ?: palette.lastIndex
        return palette[index]
    }
}

/** Round tinted badge with an icon — used for categories and accounts (docs/06 CategoryIcon). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}
