package org.multichat.android
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class SmokeTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun screenshot(name:String) {
        rule.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).let {bitmap -> File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun firstRunSettingsAndOfflineControls() {
        rule.runOnIdle {
            assertEquals(Profile(),rule.activity.model.profile)
            assertFalse(rule.activity.model.hasObsToken())
            assertTrue(rule.activity.model.channels.isEmpty())
            assertEquals("",rule.activity.model.twitchLogin)
        }
        rule.onNodeWithTag("server-url").assertTextContains("")
        screenshot("01-setup")
        rule.onNodeWithTag("server-url").performTextInput("http://invalid.example.com")
        rule.onNodeWithTag("save-setup").performScrollTo().performClick()
        rule.onNodeWithTag("setup-error").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("skip-setup").performScrollTo().performClick()
        rule.onNodeWithTag("tab-obs").performClick()
        rule.onNodeWithTag("obs-stream").assertIsNotEnabled()
        rule.onNodeWithTag("obs-status").assertTextEquals("OBS管理者設定が必要です")
        screenshot("02-obs")
        rule.onNodeWithTag("tab-comment").performClick()
        rule.onNodeWithTag("send-comment").performScrollTo().assertIsNotEnabled()
        screenshot("03-comment")
        rule.onNodeWithTag("tab-chat").performClick()
        rule.runOnIdle {
            val vm=rule.activity.model
            val method=AppModel::class.java.getDeclaredMethod("receive",Event::class.java).apply {isAccessible=true}
            method.invoke(vm,Event(id="demo-1",platform=Platform.TWITCH,channelName="demo-channel",userName="Viewer",message="こんにちは！ Android版のテストです。"))
            method.invoke(vm,Event(id="demo-2",platform=Platform.KICK,channelName="demo-channel",userName="Guest",message="配信を応援しています！",kind="subscription",amount="1か月"))
            method.invoke(vm,Event(id="demo-3",platform=Platform.YOUTUBE,channelName="demo-channel",userName="YouTube viewer",message="Hello from YouTube"))
        }
        rule.onNodeWithText("こんにちは！ Android版のテストです。").assertExists()
        screenshot("04-chat")
        rule.onNodeWithTag("open-settings").performClick()
        rule.onNodeWithText("接続セットアップ").assertExists()
        screenshot("05-settings")
        rule.runOnIdle {
            val host=AlertHost(rule.activity)
            host.update(mapOf("fixture" to "https://example.invalid/widget"),0,true)
            assertEquals(1,host.childCount)
            host.pause();assertEquals(0,host.childCount)
            host.resume();assertEquals(1,host.childCount)
            host.destroy()
            val context=rule.activity
            val store=SecureStore(context)
            store.put("test-secret","test-only-value")
            assertEquals("test-only-value",store.get("test-secret"))
            assertFalse(context.getSharedPreferences("secure-v1",0).getString("test-secret","")!!.contains("test-only-value"))
            store.put("test-secret","")
            val vm=rule.activity.model
            vm.callback("multichat://oauth-complete?state=wrong&platform=kick&account_id=bad&channel=bad&send_key=bad")
            assertTrue(vm.channels.isEmpty())
            assertEquals("",vm.store.get("kick-bad"))
        }
    }
}
