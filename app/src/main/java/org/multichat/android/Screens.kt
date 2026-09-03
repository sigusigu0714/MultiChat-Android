@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.multichat.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Purple=Color(0xFF8978F5)
private fun Platform.color() = when(this) { Platform.TWITCH -> Color(0xFFAC85FF); Platform.KICK -> Color(0xFF47C663); Platform.YOUTUBE -> Color(0xFFE85F68) }
@Composable fun MultiChatApp(vm: AppModel, overlay: (Boolean)->Unit, notifications: ()->Unit) {
    val dark=when(vm.settings.theme) { "dark"->true; "light"->false; else->isSystemInDarkTheme() }
    val colors=if(dark) darkColorScheme(primary=Purple,secondary=Color(0xFF65D5D0),background=Color(0xFF10111B),surface=Color(0xFF171824)) else lightColorScheme(primary=Color(0xFF6651C6),secondary=Color(0xFF167A80))
    MaterialTheme(colorScheme=colors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var sheet by rememberSaveable { mutableStateOf("") }
        var setup by rememberSaveable { mutableStateOf(!vm.settings.setupDone) }
        SideEffect { overlay(tab==0 && sheet.isEmpty() && !setup) }
        val snackbar=remember { SnackbarHostState() }
        LaunchedEffect(vm.notice) { if(vm.notice.isNotBlank()) { val message=vm.notice; vm.notice=""; snackbar.showSnackbar(message) } }
        Scaffold(snackbarHost={ SnackbarHost(snackbar) }, topBar={
            TopAppBar(title={ Column { Text(if(tab==0) "MultiChat" else if(tab==1) "OBS Remote" else "コメント",fontWeight=FontWeight.Bold); if(tab==0) Text(vm.chatStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary) } },actions={
                if(tab==0) IconButton(onClick={tab=2}) { Icon(Icons.AutoMirrored.Filled.Send,"コメント送信") }
                IconButton(onClick={sheet="settings"},modifier=Modifier.testTag("open-settings")) { Icon(Icons.Default.Settings,"設定") }
            })
        },bottomBar={ NavigationBar {
            NavigationBarItem(selected=tab==0,onClick={tab=0},icon={Icon(Icons.AutoMirrored.Filled.Chat,"チャット")},label={Text("MultiChat")},modifier=Modifier.testTag("tab-chat"))
            NavigationBarItem(selected=tab==1,onClick={tab=1},icon={Icon(Icons.Default.Tune,"OBS")},label={Text("OBS")},modifier=Modifier.testTag("tab-obs"))
            NavigationBarItem(selected=tab==2,onClick={tab=2},icon={Icon(Icons.AutoMirrored.Filled.Send,"コメント")},label={Text("コメント")},modifier=Modifier.testTag("tab-comment"))
        } }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { when(tab) { 0->ChatScreen(vm,{sheet="channels"}); 1->ObsScreen(vm,{sheet="obs-admin"}); else->CommentScreen(vm) } } }
        if(setup) FullPage("接続セットアップ",{setup=false; if(!vm.settings.setupDone) vm.changeSettings(vm.settings.copy(setupDone=true))}) { SetupScreen(vm) { setup=false } }
        when(sheet) {
            "settings"->FullPage("設定",{sheet=""}) { SettingsScreen(vm,{sheet="";setup=true},{sheet="channels"},{sheet="obs-admin"},notifications) }
            "channels"->FullPage("チャンネルとアラート",{sheet=""}) { ChannelScreen(vm) }
            "obs-admin"->FullPage("OBS管理者設定",{sheet=""}) { ObsAdminScreen(vm) }
        }
    }
}
@Composable private fun FullPage(title: String, close: ()->Unit, content: @Composable ()->Unit) {
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column { TopAppBar(title={Text(title)},navigationIcon={IconButton(onClick=close){Icon(Icons.Default.Close,"閉じる")}}); Box(Modifier.weight(1f).fillMaxWidth()) { content() } } }
    }
}
@Composable private fun Section(title: String, content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary); content() } }
}
@Composable private fun Toggle(label: String, checked: Boolean, change: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f)); Switch(checked,onCheckedChange=change) }
}
@Composable private fun Field(label: String, value: String, change: (String)->Unit, secret: Boolean=false, tag: String="", hint: String="") {
    OutlinedTextField(value,onValueChange=change,label={Text(label)},placeholder={if(hint.isNotBlank()) Text(hint)},singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag),visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,keyboardOptions=KeyboardOptions(keyboardType=if(secret) KeyboardType.Password else KeyboardType.Text,autoCorrectEnabled=false))
}
@Composable private fun SetupScreen(vm: AppModel, done: ()->Unit) {
    var server by remember { mutableStateOf(vm.profile.serverURL) }; var relay by remember { mutableStateOf(vm.profile.obsRelayURL) }; var client by remember { mutableStateOf(vm.profile.twitchClientID) }; var redirect by remember { mutableStateOf(vm.profile.twitchRedirectURI) }
    var error by remember { mutableStateOf("") }; var preview by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) runCatching { context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer=ByteArray(16385); var count=0
            while(count<buffer.size) { val n=stream.read(buffer,count,buffer.size-count); if(n<0) break; count+=n }
            require(count<=16384); Profile.parse(String(buffer,0,count,Charsets.UTF_8))
        } ?: error("file") }.onSuccess { server=it.serverURL;relay=it.obsRelayURL;client=it.twitchClientID;redirect=it.twitchRedirectURI;preview=true;error="" }.onFailure { error="設定ファイルを読み込めません。形式・項目・16KB以内かを確認してください" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("あなたのサーバーにつなぐ",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("接続先は空の状態です。ご自身のMultiChat対応サーバーを設定してください。管理者からJSON設定ファイルを受け取ると入力を省略できます。")
        OutlinedButton(onClick={importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile,null); Spacer(Modifier.width(8.dp)); Text("接続設定ファイルを読み込む") }
        if(preview) Text("読み込みました。接続先を確認してから保存してください。",color=MaterialTheme.colorScheme.primary)
        Section("チャット") { Field("サーバーURL（HTTPS）",server,{server=it},tag="server-url",hint="https://chat.example.com") }
        Section("OBS操作（任意）") { Field("OBSリレーURL（WSS）",relay,{relay=it}); Text("管理者トークンは保存後に「OBS管理者設定」で入力します。",style=MaterialTheme.typography.bodySmall) }
        Section("Twitchコメント送信（任意）") { Field("Twitch Client ID",client,{client=it}); Field("Twitch戻り先（HTTPS）",redirect,{redirect=it}); Text("Client IDと戻り先を両方設定します。Client Secretは不要です。",style=MaterialTheme.typography.bodySmall) }
        if(server!=vm.profile.serverURL && vm.profile.serverURL.isNotBlank()) Text("接続先を変更すると以前のチャンネルと送信キーを端末から削除します。")
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("setup-error"))
        Button(onClick={runCatching { vm.saveProfile(Profile(server.trim(),relay.trim(),client.trim(),redirect.trim())) }.onSuccess {done()}.onFailure { error=it.message ?: "設定内容を確認してください" }},modifier=Modifier.fillMaxWidth().testTag("save-setup")) { Text("保存してはじめる") }
        TextButton(onClick={vm.changeSettings(vm.settings.copy(setupDone=true));done()},modifier=Modifier.align(Alignment.CenterHorizontally).testTag("skip-setup")) { Text("あとで設定する") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun ChatScreen(vm: AppModel, channels: ()->Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }; var autoScroll by rememberSaveable { mutableStateOf(true) }
    val list=rememberLazyListState(); val events=vm.events.filter { filter=="all" || it.platform.wire==filter }
    LaunchedEffect(events.lastOrNull()?.key,autoScroll) { if(autoScroll && events.isNotEmpty()) list.animateScrollToItem(events.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedButton(onClick=channels) { Icon(Icons.Default.Add,null);Text("連携 ${vm.channels.size}/10") }; Spacer(Modifier.weight(1f))
            IconToggleButton(checked=vm.settings.ttsEnabled,onCheckedChange={vm.changeSettings(vm.settings.copy(ttsEnabled=it))}) { Icon(if(vm.settings.ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,"読み上げ") }
            IconToggleButton(checked=vm.settings.alertsVisible,onCheckedChange={vm.changeSettings(vm.settings.copy(alertsVisible=it))}) { Icon(if(vm.settings.alertsVisible) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,"アラート表示") }
            IconButton(onClick={vm.reconnect()}) { Icon(Icons.Default.Refresh,"再接続") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=filter=="all",onClick={filter="all"},label={Text("すべて")})
            Platform.entries.forEach { platform -> FilterChip(selected=filter==platform.wire,onClick={filter=platform.wire},label={Text(platform.label)}) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("重複を非表示 ${vm.hiddenDuplicates}件",style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f)); TextButton(onClick={autoScroll=!autoScroll}) { Text(if(autoScroll) "自動スクロール ON" else "最新へ") }
        }
        if(events.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Filled.Chat,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
                Text("コメントを待っています",style=MaterialTheme.typography.titleMedium)
                Text(if(vm.profile.serverURL.isBlank()) "設定から接続先を登録し、チャンネルを連携してください。" else "接続済みのチャンネルのコメントと配信アラートをここに表示します。",style=MaterialTheme.typography.bodyMedium)
            }
        } else LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(if(vm.settings.density=="compact") 4.dp else 8.dp)) { items(events,key={it.key}) { EventRow(it,vm.settings) {vm.translate(it)} } }
    }
}
@Composable private fun EventRow(event: Event, settings: Settings, translate: ()->Unit) {
    val padding=when(settings.density) { "compact"->8.dp;"comfortable"->18.dp;else->12.dp }
    Card(colors=CardDefaults.cardColors(containerColor=if(event.isAlert) event.platform.color().copy(alpha=.14f) else MaterialTheme.colorScheme.surfaceContainer),shape=RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(padding),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                if(safeWidgetURL(event.avatar)) AsyncImage(event.avatar,null,Modifier.size(26.dp))
                Text(event.platform.label,color=event.platform.color(),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                Text(event.userName,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f),maxLines=1)
                event.badges.take(6).forEach { if(safeWidgetURL(it.url)) AsyncImage(it.url,it.name,Modifier.size(18.dp)) }
                Text(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.labelSmall)
            }
            Text(event.channelName,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(event.isAlert) Text(listOf(event.kind,event.amount).filter { it.isNotBlank() }.joinToString(" · "),fontWeight=FontWeight.Bold,color=event.platform.color())
            if(settings.showOriginal || event.translation.isBlank()) EmoteText(event.message,event.emotes,settings.fontSize)
            if(event.translation.isNotBlank()) Text("日本語: ${event.translation}",fontSize=settings.fontSize.sp,color=MaterialTheme.colorScheme.secondary)
            if(event.message.isNotBlank()) TextButton(onClick=translate,enabled=!event.translating && event.translation.isBlank(),contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=28.dp)) { Text(if(event.translating) "翻訳中…" else if(event.translation.isNotBlank()) "翻訳済み" else "日本語に翻訳",style=MaterialTheme.typography.labelSmall) }
        }
    }
}
@Composable private fun EmoteText(message: String, emotes: List<Emote>, size: Float) {
    val parts=remember(message,emotes) { messageParts(message,emotes) }
    val inline=parts.mapIndexedNotNull { index,part -> part.emoteURL?.let { url -> "emote-$index" to InlineTextContent(Placeholder((size*1.5).sp,(size*1.5).sp,PlaceholderVerticalAlign.Center)) { AsyncImage(url,part.text,Modifier.fillMaxSize()) } } }.toMap()
    val text=buildAnnotatedString { parts.forEachIndexed { index,part -> if(part.emoteURL==null) append(part.text) else appendInlineContent("emote-$index",part.text) } }
    Text(text,inlineContent=inline,fontSize=size.sp,lineHeight=(size*1.6).sp)
}
@Composable private fun SettingsScreen(vm: AppModel, setup: ()->Unit, channels: ()->Unit, obs: ()->Unit, notifications: ()->Unit) {
    val s=vm.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Section("接続とアカウント") { OutlinedButton(onClick=setup,modifier=Modifier.fillMaxWidth()) {Text("接続セットアップ")}; OutlinedButton(onClick=channels,modifier=Modifier.fillMaxWidth()) {Text("チャンネルとアラート")}; OutlinedButton(onClick=obs,modifier=Modifier.fillMaxWidth()) {Text("OBS管理者設定")} }
        Section("表示") {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("system" to "端末に合わせる","light" to "ライト","dark" to "ダーク").forEach { (value,label) -> FilterChip(s.theme==value,{vm.changeSettings(s.copy(theme=value))},label={Text(label)}) } }
            Toggle("画面をスリープさせない",s.awake) {vm.changeSettings(s.copy(awake=it))}
            Text("文字サイズ ${s.fontSize.toInt()}"); Slider(s.fontSize,{vm.changeSettings(s.copy(fontSize=it))},valueRange=12f..30f,steps=17)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("compact" to "コンパクト","standard" to "標準","comfortable" to "ゆったり").forEach { (value,label) -> FilterChip(s.density==value,{vm.changeSettings(s.copy(density=value))},label={Text(label)}) } }
        }
        Section("読み上げ") {
            Toggle("読み上げを有効にする",s.ttsEnabled) {vm.changeSettings(s.copy(ttsEnabled=it))}; Toggle("名前を読む",s.readNames) {vm.changeSettings(s.copy(readNames=it))}; Toggle("アラートを読む",s.readAlerts) {vm.changeSettings(s.copy(readAlerts=it))}
            Text("速度 ${"%.2f".format(s.speechRate)}倍"); Slider(s.speechRate,{vm.changeSettings(s.copy(speechRate=it))},valueRange=.25f..2f)
            Field("読み上げ除外ユーザー（空白・カンマ区切り）",s.ignoredUsers,{vm.changeSettings(s.copy(ignoredUsers=it))})
            Row { TextButton(onClick=vm::testSpeech) {Text("テスト")}; TextButton(onClick=vm::stopSpeech) {Text("停止")} }
        }
        Section("翻訳") { Toggle("自動で日本語に翻訳",s.autoTranslate) {vm.changeSettings(s.copy(autoTranslate=it))}; Toggle("原文も表示",s.showOriginal) {vm.changeSettings(s.copy(showOriginal=it))}; Text("初回は言語モデルをダウンロードします。その後の翻訳は端末内で処理します。",style=MaterialTheme.typography.bodySmall) }
        Section("重複除去") { Toggle("Twitch統合チャットの重複を除去",s.integratedDedupe) {vm.changeSettings(s.copy(integratedDedupe=it))}; Text("判定時間 ${"%.1f".format(s.duplicateWindow)}秒"); Slider(s.duplicateWindow,{vm.changeSettings(s.copy(duplicateWindow=it))},valueRange=.5f..10f); Text("同じメッセージIDの再受信は常に除去します。",style=MaterialTheme.typography.bodySmall) }
        Section("アラート") { Toggle("アラートを画面に表示",s.alertsVisible) {vm.changeSettings(s.copy(alertsVisible=it))}; TextButton(onClick=vm::refreshAlerts) {Text("アラートウィジェットを再読み込み")}; TextButton(onClick=notifications) {Text("配信アラートの通知を許可")} }
        Text("MultiChat for Android 1.0.0\n接続先・認証情報は端末内で暗号化して保存します。",style=MaterialTheme.typography.bodySmall); Spacer(Modifier.height(16.dp))
    }
}
@Composable private fun ChannelScreen(vm: AppModel) {
    var platform by remember { mutableStateOf(Platform.TWITCH) }; var value by remember { mutableStateOf("") }; var editing by remember { mutableStateOf<Channel?>(null) }; var deleting by remember { mutableStateOf<Channel?>(null) }
    val context=LocalContext.current
    fun login(p: Platform) { runCatching { CustomTabsIntent.Builder().build().launchUrl(context,Uri.parse(vm.serverLoginURL(p))) }.onFailure { vm.notice=it.message ?: "ログインを開始できません" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Section("アカウント連携 (${vm.channels.size}/10)") {
            Text("ご自身のサーバーでログインしてチャンネルを追加します。")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Platform.entries.forEach { p -> OutlinedButton(onClick={login(p)},enabled=!vm.busy && vm.profile.serverURL.isNotBlank() && vm.channels.size<10) {Text(p.label)} } }
            TextButton(onClick=vm::syncChannels,enabled=!vm.busy) {Text("サーバーから再読み込み")}
        }
        Section("公開チャンネルを視聴") {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(Platform.TWITCH,Platform.YOUTUBE).forEach { p -> FilterChip(platform==p,{platform=p},label={Text(p.label)}) } }
            Field(if(platform==Platform.TWITCH) "チャンネル名" else "チャンネルURL / @ハンドル",value,{value=it})
            Button(onClick={vm.addWatch(platform,value)},enabled=value.isNotBlank() && !vm.busy && vm.channels.size<10 && vm.profile.serverURL.isNotBlank()) {Text("追加")}
        }
        vm.channels.forEach { channel -> Section("${channel.platform.label} · ${channel.name}") {
            Toggle("有効",channel.enabled) {vm.updateChannel(channel.copy(enabled=it),vm.alertURL(channel))}; Text(channel.identifier,style=MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick={editing=channel}) {Text("アラート設定")}; TextButton(onClick={deleting=channel},enabled=!vm.busy) {Text("削除",color=MaterialTheme.colorScheme.error)} }
        } }
    }
    editing?.let { channel ->
        var url by remember(channel.id) { mutableStateOf(vm.alertURL(channel)) }; var provider by remember(channel.id) {mutableStateOf(channel.alertProvider)}; var error by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={editing=null},title={Text("アラート設定")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("ウィジェットURLは暗号化して保存します。")
            listOf("Streamlabs","StreamElements").forEach { name -> Row(verticalAlignment=Alignment.CenterVertically) {RadioButton(provider==name,{provider=name});Text(name)} }
            Field("ウィジェットURL（HTTPS）",url,{url=it},secret=true); if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick={runCatching {vm.updateChannel(channel.copy(alertProvider=provider),url)}.onSuccess {editing=null}.onFailure {error="HTTPSのウィジェットURLを確認してください"}}){Text("保存")}},dismissButton={TextButton(onClick={editing=null}){Text("キャンセル")}})
    }
    deleting?.let { channel -> AlertDialog(onDismissRequest={deleting=null},title={Text("チャンネルを削除しますか？")},text={Text("${channel.name} の連携をサーバーから削除します。同じサーバーを使う他の端末にも影響します。")},confirmButton={TextButton(onClick={vm.removeChannel(channel);deleting=null}){Text("削除")}},dismissButton={TextButton(onClick={deleting=null}){Text("キャンセル")}}) }
}

@Composable private fun CommentScreen(vm: AppModel) {
    var platform by rememberSaveable {mutableStateOf(Platform.TWITCH)}; var message by rememberSaveable {mutableStateOf("")}; var account by rememberSaveable {mutableStateOf("")}
    val kicks=vm.channels.filter {it.platform==Platform.KICK && it.accountID.isNotBlank()}
    LaunchedEffect(kicks) {if(kicks.none {it.accountID==account}) account=kicks.firstOrNull()?.accountID.orEmpty()}
    val context=LocalContext.current; val focus=LocalFocusManager.current
    fun login(direct: Boolean) {runCatching {CustomTabsIntent.Builder().build().launchUrl(context,Uri.parse(if(direct) vm.twitchLoginURL() else vm.serverLoginURL(Platform.KICK)))}.onFailure {vm.notice=it.message ?: "ログインを開始できません"}}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(Platform.TWITCH,Platform.KICK).forEach { p -> FilterChip(platform==p,{platform=p},label={Text(p.label)}) } }
        Section("送信アカウント") {
            if(platform==Platform.TWITCH) {
                Text(if(vm.twitchLogin.isBlank()) "未ログイン" else "ログイン中: ${vm.twitchLogin}")
                Row { OutlinedButton(onClick={login(true)}) {Text("Twitchログイン")}; if(vm.twitchLogin.isNotBlank()) TextButton(onClick=vm::clearTwitch) {Text("ログアウト")} }
                Field("送信先チャンネル",vm.settings.targetChannel,{vm.changeSettings(vm.settings.copy(targetChannel=it))},tag="comment-target")
            } else {
                kicks.forEach { channel -> Row(verticalAlignment=Alignment.CenterVertically) {RadioButton(account==channel.accountID,{account=channel.accountID});Text(channel.name)} }
                if(kicks.isEmpty()) Text("KICKアカウントが未連携です"); OutlinedButton(onClick={login(false)}) {Text("KICKを連携 / 再連携")}
            }
        }
        Section("コメント") {
            OutlinedTextField(message,{message=it},label={Text("メッセージ")},modifier=Modifier.fillMaxWidth().heightIn(min=140.dp).testTag("comment-message"),maxLines=8)
            Text("${message.codePointCount(0,message.length)} / 500",style=MaterialTheme.typography.labelSmall,modifier=Modifier.align(Alignment.End))
            val ready=!vm.sending && if(platform==Platform.TWITCH) vm.twitchLogin.isNotBlank() && vm.settings.targetChannel.isNotBlank() else account.isNotBlank()
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick={focus.clearFocus();vm.sendComment(platform,vm.settings.targetChannel,account,message){message=""}},enabled=ready && message.isNotBlank(),modifier=Modifier.weight(1f).testTag("send-comment")) {Text(if(vm.sending) "送信中…" else "送信")}
                OutlinedButton(onClick={focus.clearFocus();vm.sendComment(platform,vm.settings.targetChannel,account,"!fix")},enabled=ready) {Text("!fix")}
            }
        }
        Text("YouTubeは統合チャットの受信に対応します。コメント送信はiPhone版と同じくTwitch・KICKが対象です。",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun ObsAdminScreen(vm: AppModel) {
    var url by remember {mutableStateOf(vm.profile.obsRelayURL)}; var token by remember {mutableStateOf("")}; var error by remember {mutableStateOf("")}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("ご自身のOBSリレーと管理者トークンを登録してください。",style=MaterialTheme.typography.bodyLarge)
        Field("OBSリレーURL（WSS）",url,{url=it},tag="obs-url"); Field(if(vm.hasObsToken()) "新しい管理者トークン（登録済み）" else "管理者トークン",token,{token=it},secret=true,tag="obs-token")
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={runCatching {require(token.isNotBlank() || (vm.hasObsToken() && url.trim()==vm.profile.obsRelayURL));vm.saveProfile(vm.profile.copy(obsRelayURL=url.trim()));if(token.isNotBlank()) vm.saveObsToken(token);token="";vm.notice="OBS設定を保存しました"}.onFailure {error="WSSのURLと管理者トークンを確認してください"}},modifier=Modifier.fillMaxWidth()) {Text("保存して接続")}
        OutlinedButton(onClick={vm.saveObsToken("");token=""},modifier=Modifier.fillMaxWidth()) {Text("トークンを削除して切断")}
    }
}
@Composable private fun ObsScreen(vm: AppModel, admin: ()->Unit) {
    val obs=vm.obs; var confirmStop by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Section("接続状況") {
            Text(if(!vm.hasObsToken() || vm.profile.obsRelayURL.isBlank()) "OBS管理者設定が必要です" else obs.status,modifier=Modifier.testTag("obs-status"))
            Text("リレー: ${if(obs.connected) "接続" else "未接続"}  /  エージェント: ${if(obs.agentOnline) "接続" else "未接続"}  /  OBS: ${if(obs.obsOnline) "接続" else "未接続"}",style=MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick=admin) {Text("管理者設定")}; TextButton(onClick=vm::reconnectObs) {Text("再接続")}; TextButton(onClick={vm.obsAction("refresh")},enabled=obs.connected && obs.agentOnline) {Text("更新")} }
        }
        Section("配信") { Text(if(obs.streaming) "配信中" else "停止中",style=MaterialTheme.typography.headlineSmall); Button(onClick={if(obs.streaming) confirmStop=true else vm.obsAction("start_stream")},enabled=obs.ready,modifier=Modifier.fillMaxWidth().testTag("obs-stream")) {Text(if(obs.streaming) "配信を停止" else "配信を開始")} }
        Section("シーン") { if(obs.scenes.isEmpty()) Text("OBS接続後にシーンを表示します"); obs.scenes.forEach { scene -> OutlinedButton(onClick={vm.obsAction("set_scene",scene)},enabled=obs.ready,modifier=Modifier.fillMaxWidth()) {Text((if(scene==obs.currentScene) "● " else "")+scene)} } }
        Section("ソース表示") { if(obs.sources.isEmpty()) Text("現在のシーンのソースがここに表示されます"); obs.sources.forEach { source -> Row(verticalAlignment=Alignment.CenterVertically) {Text(source.name,Modifier.weight(1f));Switch(source.enabled,{vm.obsAction("set_source_visible",obs.currentScene,source.name,it)},enabled=obs.ready)} } }
        Section("リレーから !fix") { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {OutlinedButton(onClick={vm.obsAction("twitch_fix")},enabled=obs.connected && obs.agentOnline){Text("Twitch !fix")};OutlinedButton(onClick={vm.obsAction("kick_fix")},enabled=obs.connected && obs.agentOnline){Text("KICK !fix")}}; Text("自分のアカウントから送る場合は「コメント」画面を使います。",style=MaterialTheme.typography.bodySmall) }
    }
    if(confirmStop) AlertDialog(onDismissRequest={confirmStop=false},title={Text("配信を停止しますか？")},text={Text("現在のOBS配信を停止します。")},confirmButton={TextButton(onClick={confirmStop=false;vm.obsAction("stop_stream")}){Text("配信を停止")}},dismissButton={TextButton(onClick={confirmStop=false}){Text("キャンセル")}})
}
