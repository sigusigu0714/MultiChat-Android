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
        // The AOSP launcher can show an unrelated ANR during emulator cold boot.
        // Stop only the launcher; never dismiss or suppress an error from this app.
        val launcherStop=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop com.android.launcher3")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(launcherStop).use { it.readBytes() }
        android.os.SystemClock.sleep(500)
        rule.waitForIdle()
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
        // Doneru can be configured without connecting any chat account or server.
        rule.onNodeWithTag("doneru-url").performScrollTo().performTextInput("https://example.invalid/alert-box?key=test-only")
        rule.onNodeWithTag("save-doneru").performScrollTo().performClick()
        rule.runOnIdle {
            val vm=rule.activity.model
            assertTrue(vm.channels.isEmpty())
            assertEquals("https://example.invalid/alert-box?key=test-only",vm.activeAlertURLs()["doneru-standalone"])
            assertEquals(vm.doneruWidgetURL,SecureStore(rule.activity).get("doneru-widget-url"))
            assertFalse(rule.activity.getSharedPreferences("secure-v1",0).getString("doneru-widget-url","")!!.contains("test-only"))
        }
        screenshot("06-doneru-settings")
        rule.onNodeWithTag("delete-doneru").performScrollTo().performClick()
        rule.runOnIdle { assertFalse(rule.activity.model.activeAlertURLs().containsKey("doneru-standalone")) }

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
        rule.onNodeWithTag("widget-url-4").performScrollTo().performTextInput("https://example.invalid/fifth")
        rule.onNodeWithTag("save-widget-4").performScrollTo().performClick()
        rule.runOnIdle {
            val vm=rule.activity.model
            assertEquals("https://example.invalid/fifth",vm.standaloneWidgetURL(4))
            assertEquals(vm.standaloneWidgetURL(4),SecureStore(rule.activity).get("standalone-widget-4"))
            for(index in 0 until 4)vm.saveStandaloneWidget(index,"https://example.invalid/widget-$index")
            assertEquals(5,vm.activeAlertURLs().size)
            assertTrue(runCatching {vm.saveStandaloneWidget(5,"https://example.invalid/sixth")}.isFailure)
            assertTrue(runCatching {vm.saveStandaloneWidget(2,"http://example.invalid/unsafe")}.isFailure)
            assertEquals("https://example.invalid/widget-2",vm.standaloneWidgetURL(2))
            vm.saveStandaloneWidget(2,"")
            assertEquals(4,vm.activeAlertURLs().size)
        }
        screenshot("08-five-widgets")
        rule.runOnIdle {for(index in 0 until 5)rule.activity.model.saveStandaloneWidget(index,"")}
        rule.onNodeWithContentDescription("閉じる").performClick()
        rule.waitForIdle()
        // A small alert on a large transparent desktop canvas, using a real WebView.
        lateinit var widget: AlertWidget
        lateinit var root: android.widget.FrameLayout
        rule.runOnIdle {
            root=rule.activity.findViewById(android.R.id.content)
            widget=AlertWidget(rule.activity,true)
            root.addView(widget,android.widget.FrameLayout.LayoutParams(-1,-1))
            widget.setBackgroundColor(android.graphics.Color.WHITE)
            widget.web.loadDataWithBaseURL("https://streamelements.com/", """
                <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
                html,body {margin:0;background:transparent;width:1920px;height:1080px;}
                #alert {position:absolute;left:70px;top:440px;width:500px;height:210px;background:#163955;color:white;border:4px solid #25dfa6;box-sizing:border-box;}
                .media {width:140px;height:120px;margin:12px auto;background:#f5a34c;border-radius:28px;}
                p {font:24px sans-serif;margin:0;text-align:center;}
                </style></head><body><div id="alert"><div class="media"></div><p>ALERT CONTENT</p></div>
                <div style="opacity:0;position:absolute;width:1920px;height:1080px;background:red">HIDDEN</div></body></html>
            """, "text/html","UTF-8",null)
        }
        rule.waitUntil(15000) { widget.fittedContent != null }
        rule.runOnIdle {
            val rect=checkNotNull(widget.fittedContent)
            assertTrue(rect.width()>widget.width*0.8f)
            assertTrue(rect.height()>widget.height*0.1f)
            assertTrue(rect.left>=0 && rect.top>=0 && rect.right<=widget.width && rect.bottom<=widget.height)
            assertEquals(widget.width/2f,rect.centerX(),2f)
            assertEquals(widget.height/2f,rect.centerY(),2f)
        }
        android.os.SystemClock.sleep(1000) // Let the compositor present the transformed WebView.
        screenshot("07-alert-content")
        rule.runOnIdle { root.removeView(widget);widget.destroy() }
    }
}
