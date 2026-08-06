package com.celzero.bravedns.ui.fragment

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.shadows.ShadowBackend
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.*

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class RulesImportHelperFuzzTest {

    private val mockContext: Context = mockk(relaxed = true)
    private val mockContentResolver: ContentResolver = mockk(relaxed = true)
    private val testUri: Uri = Uri.parse("content://com.test.provider/rules.txt")
    private val random = Random()

    @Before
    fun setup() {
        every { mockContext.contentResolver } returns mockContentResolver
        mockkObject(IpRulesManager)
        mockkObject(DomainRulesManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `parseFile should never crash on random junk input`() = runTest {
        val iterations = 50
        repeat(iterations) {
            val fuzzContent = generateRandomJunk()
            every { mockContentResolver.openInputStream(testUri) } returns fuzzContent.byteInputStream()
            
            // Should not throw regardless of junk
            val resultIp = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)
            assertNotNull(resultIp)

            every { mockContentResolver.openInputStream(testUri) } returns fuzzContent.byteInputStream()
            val resultDomain = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)
            assertNotNull(resultDomain)
        }
    }

    @Test
    fun `parseFile should handle extremely long lines without OOM`() = runTest {
        val longLine = "a".repeat(1024 * 1024) // 1MB line
        every { mockContentResolver.openInputStream(testUri) } returns longLine.byteInputStream()
        
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)
        assertNotNull(result)
    }

    private fun generateRandomJunk(): String {
        val sb = StringBuilder()
        val lines = random.nextInt(100)
        repeat(lines) {
            val lineLength = random.nextInt(500)
            repeat(lineLength) {
                sb.append(random.nextInt(256).toChar())
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
