package com.pdfmaster.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
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
        rule.onAllNodesWithText("PDF Master")[0].assertExists()
        shot("01_home")
        for (t in Tools.all) {
            rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(t.title))
            rule.onAllNodesWithText(t.title)[0].performClick()
            rule.onAllNodesWithText(t.action)[0].assertExists()
            if (t.id in setOf("merge", "compress", "watermark", "protect", "translate", "pdf2word", "sign")) shot("tool_${t.id}")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onAllNodesWithText("PDF Master")[0].assertExists()
        }
    }

    @Test fun menuAndThemes() {
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithTag("cat_EDIT").performClick()
        rule.onNodeWithText("Edit PDF").assertExists()
        shot("03_menu_light")
        rule.onNodeWithTag("drawer").performScrollToNode(hasText("APPEARANCE"))
        rule.onNodeWithText("Dark").performClick()
        rule.onNodeWithTag("drawer").performScrollToNode(hasText("Edit PDF"))
        shot("04_menu_dark")
        rule.onNodeWithText("Edit PDF").performClick()
        rule.onNodeWithText("Open editor").assertExists()
        shot("05_tool_dark")
        rule.onNodeWithContentDescription("Back").performClick()
        shot("06_home_dark")
        rule.onNodeWithContentDescription("Switch to light theme").performClick()
        rule.onNodeWithContentDescription("Switch to dark theme").assertExists()
    }

    @Test fun searchFilters() {
        rule.onNodeWithText("Search tools").performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("excel")
        rule.onNodeWithText("Excel to PDF").assertIsDisplayed()
        rule.onNodeWithText("PDF to Excel").assertIsDisplayed()
        shot("02_search")
    }
}
