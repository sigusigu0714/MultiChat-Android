# 管理者向け接続仕様

## MultiChatサーバー

`serverURL` はHTTPS。APIは指定URLのオリジン直下を使います。

- `GET /api/accounts`: id, platform, displayName, channelIdentifier の配列
- `GET /api/twitch/watch-channels` / `/api/youtube/watch-channels`: 視聴チャンネル一覧
- `POST /api/twitch/watch-channels`: `{"login":"..."}`
- `POST /api/youtube/watch-channels`: `{"channel":"..."}`
- `DELETE /api/{platform}/watch-channels/{id}` または `/api/accounts/{id}`
- `GET /oauth/{platform}/start?return_to=...`: ブラウザーでアカウント連携
- WSS `/ws`: iPhone版と同じUnifiedEvent JSONを1メッセージごとに送信
- `POST /api/kick/chat`: `{"accountId":"...","content":"..."}`、`X-Account-Send-Key` ヘッダー

OAuthの `return_to` は `multichat://oauth-complete?state=<ランダム値>` です。サーバーはこのURLのstateを保持したままplatform/name/channel/account_idを追加して返してください。KICKはsend_keyも追加します。アプリは10分以内・同じ用途のstateを1回だけ受け付けます。

サーバー管理者はAPI・WebSocket・アカウントの削除や変更を適切なアクセス制御で保護してください。クライアント側の暗号化はサーバーの認可を代替しません。本アプリは既存iPhone互換API向けで、独自の共通Bearer認証方式を追加していません。

## Twitch送信

`twitchClientID` と `twitchRedirectURI`（HTTPS）を設定します。戻り先サーバーはTwitchの認可結果を `obsremote` スキームに戻し、access_tokenとstateを保持してください。Androidアプリが送ったstateを削除・置換しないでください。

要求するスコープは `user:write:chat`。アプリはTwitchのvalidate APIでClient ID・ユーザーID・権限を確認し、Helix Chat Messages APIで投稿します。Client Secretをアプリに含める必要はありません。

## OBSリレー

WSS接続後、`{"type":"auth","role":"client","token":"..."}` を送ります。

受信: `auth_ok`、`agent_status`、`state`、`result`、`error`。
操作: `refresh`、`start_stream`、`stop_stream`、`set_scene`、`set_source_visible`、`twitch_fix`、`kick_fix`。
コマンドは `type=command` とランダムなidを含みます。シーン指定はsceneName、ソース指定はsourceNameとenabledです。

管理者トークンは利用者がアプリ内で入力します。JSON配布用設定には入れないでください。
