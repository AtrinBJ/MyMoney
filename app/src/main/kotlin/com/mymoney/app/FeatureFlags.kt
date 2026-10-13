package com.mymoney.app

/**
 * Features that are built in later stages. While a flag is false the feature is not shown anywhere, and nothing
 * refers to it as available (docs/FEATURES_STATUS.md).
 */
object FeatureFlags {
    /** Backup & restore (Stage 5). Until then the "backup overdue" insight would point to nothing. */
    const val BACKUP = false
}
