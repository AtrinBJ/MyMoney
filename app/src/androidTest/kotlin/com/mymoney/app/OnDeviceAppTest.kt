package com.mymoney.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.data.ReportPdfWriter
import com.mymoney.core.data.exchange.ExportFilter
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks (Stage 6, A55): the real app starts with the encrypted database, and the PDF statement is a
 * valid document Android itself can open. Read-only with respect to the user's data.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnDeviceAppTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var pdf: ReportPdfWriter

    @Before
    fun inject() = hilt.inject()

    /**
     * One test method on purpose: Hilt's test mode builds a new singleton graph per test method, which would
     * open a second settings DataStore on the same file in the same process (something the real app never does).
     */
    @Test
    fun appStartsAndPdfStatementOpensWithPdfRenderer() {
        compose.waitUntil(10_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }

        val file = File(compose.activity.cacheDir, "qa-statement.pdf")
        runBlocking { file.outputStream().use { pdf.write(it, ExportFilter()) } }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> assertTrue(r.pageCount >= 1) }
        }
        file.delete()
    }
}
