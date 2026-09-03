package org.multichat.android
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.net.URI

class CoreTest {
    private fun event(id:String="a", platform:Platform=Platform.TWITCH, message:String="Hello", time:Long=10000, source:String="") = Event(id=id,platform=platform,channelName="demo",userName="Demo",message=message,timestamp=time,sourceMessageID=source)
    @Test fun defaultsAreUnconfigured() { val p=Profile(); assertEquals("",p.serverURL); assertEquals("",p.obsRelayURL); assertEquals("",p.twitchClientID); assertEquals("",p.twitchRedirectURI); assertEquals("",Settings().targetChannel) }
    @Test fun profileRoundTrip() { val p=Profile("https://chat.example.com","wss://relay.example.com","client123","https://auth.example.com/callback"); assertEquals(p,Profile.parse(p.json().toString())) }
    @Test fun profilesRejectUnsafeEndpointsAndSecrets() {
        listOf("http://example.com","https://name:password@example.com","https://example.com?token=x","https://example.com#secret","https:///missing").forEach { bad -> assertThrows(IllegalArgumentException::class.java) { Profile(serverURL=bad).validated() } }
        assertThrows(IllegalArgumentException::class.java) { Profile.parse("""{"serverURL":"https://example.com","token":"private"}""") }
        assertThrows(IllegalArgumentException::class.java) { Profile.parse("""{"serverURL":5}""") }
        assertThrows(IllegalArgumentException::class.java) { Profile.parse(" ".repeat(16385)) }
        assertThrows(IllegalArgumentException::class.java) { Profile(twitchClientID="client").validated() }
    }
    @Test fun sourceIDIsScopedToPlatform() { val d=EventDeduplicator(); assertTrue(d.accept(event(source="same"),now=10000)); assertFalse(d.accept(event(id="b",source="same"),now=10000)); assertTrue(d.accept(event(platform=Platform.KICK,source="same"),now=10000)) }
    @Test fun unicodeDedupeAndTimeWindow() { val d=EventDeduplicator(); assertTrue(d.accept(event(message="Ｈｅｌｌｏ  café"),now=10000)); assertFalse(d.accept(event(id="b",message="hello cafe",time=12000),now=12000)); assertTrue(d.accept(event(id="c",message="hello cafe",time=14000),now=14000)) }
    @Test fun optionalDedupeDoesNotSuppressLegitimateRepeats() { val d=EventDeduplicator(); assertTrue(d.accept(event(),false)); assertTrue(d.accept(event(id="b"),false)); assertFalse(d.accept(event(id="b"),false)) }
    @Test fun systemPacketsAreNotChat() { assertFalse(EventDeduplicator().accept(event().copy(kind="system"))) }
    @Test fun emotesUseUtf16AndDontBreakEmoji() {
        val text="😀 Kappa!"; val parts=messageParts(text,listOf(Emote("Kappa","https://example.com/emote.png",3,7)))
        assertEquals(listOf("😀 ","Kappa","!"),parts.map { it.text }); assertNotNull(parts[1].emoteURL)
        val invalid=messageParts("😀",listOf(Emote("no","https://example.com/emote.png",1,1)))
        assertEquals("😀",invalid.single().text); assertNull(invalid.single().emoteURL)
    }
    @Test fun emoteFallbackRespectsWordBoundaries() { val parts=messageParts("Kappa NotKappa Kappa",listOf(Emote("Kappa","https://example.com/e.gif"))); assertEquals(2,parts.count {it.emoteURL!=null}); assertEquals("Kappa NotKappa Kappa",parts.joinToString("") {it.text}) }
    @Test fun authStateExpiresAndPurposeMustMatch() { val pending=PendingAuth("nonce","kick",1000); assertTrue(pending.matches("nonce","kick",2000)); assertFalse(pending.matches("other","kick",2000)); assertFalse(pending.matches("nonce","twitch",2000)); assertFalse(pending.matches("nonce","kick",601001)); assertFalse(pending.matches("nonce","kick",999)) }
    @Test fun ambiguousCallbacksAreRejected() { assertThrows(IllegalArgumentException::class.java) {callbackParameters(URI("obsremote://callback?state=a#state=b"))}; assertEquals("a b",callbackParameters(URI("obsremote://callback?state=a%20b"))["state"]) }
    @Test fun obsOfflineClearsStaleControls() { var state=ObsState().packet(JSONObject("""{"type":"auth_ok","agentOnline":true}""")); state=state.packet(JSONObject("""{"type":"state","obsOnline":true,"streaming":true,"currentScene":"Camera","scenes":["Camera"],"sources":[{"name":"Overlay","enabled":true}]}""")); assertTrue(state.ready); state=state.packet(JSONObject("""{"type":"agent_status","online":false}""")); assertFalse(state.ready); assertFalse(state.streaming); assertTrue(state.scenes.isEmpty()) }
    @Test fun obsSourcePacketIncludesCurrentScene() { val packet=obsCommand("set_source_visible","Scene","Source",false); assertEquals("command",packet.getString("type")); assertEquals("Scene",packet.getString("sceneName")); assertEquals("Source",packet.getString("sourceName")); assertFalse(packet.getBoolean("enabled")) }
    @Test fun speechExclusionsAndLength() { val s=Settings(ttsEnabled=true,ignoredUsers="bot Demo"); assertNull(speechText(event(),s)); val spoken=speechText(event(message="あ".repeat(200)),s.copy(ignoredUsers=""))!!; assertTrue(spoken.endsWith("以下省略")); assertTrue(spoken.length<210) }
    @Test fun backendEventDecodesWithoutOptionalFields() { val event=Event.parse(JSONObject("""{"id":"test","platform":"KICK","channelName":"demo","userName":"viewer","message":"hello","timestamp":"2026-01-01T00:00:00Z"}""")); assertEquals(Platform.KICK,event.platform); assertEquals("chat",event.kind); assertTrue(event.badges.isEmpty()); assertEquals(1767225600000,event.timestamp) }
}
