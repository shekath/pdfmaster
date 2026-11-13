package com.pdfmaster.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import com.pdfmaster.app.tools.Tools
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val shots = File(System.getProperty("pdfmaster.testOut") ?: "build/test-artifacts", "screens").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyToolOpens() {
        rule.onNodeWithText("PDF Master").assertIsDisplayed()
        shot("01_home")
        for (t in Tools.all) {
            rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(t.title))
            rule.onAllNodesWithText(t.title)[0].performClick()
            rule.onAllNodesWithText(t.action)[0].assertExists()
            if (t.id in setOf("merge", "compress", "watermark", "protect", "translate", "pdf2word", "sign")) shot("tool_${t.id}")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("PDF Master").assertIsDisplayed()
        }
    }

    @Test fun searchFilters() {
        rule.onNodeWithText("Search tools").performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("excel")
        rule.onNodeWithText("Excel to PDF").assertIsDisplayed()
        rule.onNodeWithText("PDF to Excel").assertIsDisplayed()
        shot("02_search")
    }
}
