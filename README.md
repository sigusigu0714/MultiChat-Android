# MultiChat for Android

Twitch・KICK・YouTubeの統合チャットとOBS操作をまとめたAndroidアプリです。iPhone配布版と同じサーバーAPIに対応します。

## インストール

[最新版の配布ファイル](../../releases/latest)から `MultiChat-Android.apk` をダウンロードして開きます。Android 8.0以降が必要です。Androidが確認を表示した場合は、ダウンロードに使ったブラウザーに「この提供元のアプリを許可」を設定してください。インストール後はその許可を戻せます。

1. 「接続セットアップ」で、ご自身のMultiChat対応サーバーのHTTPS URLを入力します。
2. 管理者からJSON接続設定を受け取った場合は「接続設定ファイルを読み込む」を使えます。読み込んだ内容を確認して保存します。
3. 「連携」からチャンネルを登録します。最大10チャンネルです。
4. コメント送信は「コメント」、配信操作は「OBS」を開きます。

アプリには共用サーバー、ログイン済みアカウント、個人の接続先、OBS管理者トークンを同梱していません。互換性のあるMultiChatサーバーは利用者または管理者が別途用意します。このリポジトリはクライアントアプリのみを含みます。

## 機能

- Twitch・KICK・YouTubeの統合タイムライン、サービス別フィルター、自動スクロール
- ユーザー画像、バッジ、静止・アニメーションエモート、課金・参加・レイド等のアラート
- メッセージIDとTwitch統合コメントの重複除去、判定時間の設定
- Twitch・YouTubeの公開視聴チャンネルと各サービスのアカウント連携
- Twitch・KICKのコメント送信と `!fix`
- OBSリレー認証、配信開始・停止確認、シーン切り替え、ソース表示切り替え、リレーからの `!fix`
- Streamlabs・StreamElementsウィジェットの映像・音声アラート、通知
- 日本語読み上げ、名前・アラートの読み上げ設定、速度・除外ユーザー設定
- 端末内の日本語翻訳、原文表示設定
- テーマ、文字サイズ、表示密度、画面スリープ防止
- JSON設定の読み込み、Android Keystoreを使った設定・認証情報の暗号化保存

[iPhone版との対応表](docs/PARITY.md) / [接続仕様](docs/CONNECTIONS.md) / [プライバシー](docs/PRIVACY.md)

## 設定ファイル

```json
{
  "serverURL": "https://chat.example.com"
}
```

任意項目は `obsRelayURL`・`twitchClientID`・`twitchRedirectURI` です。Twitchの2項目は両方設定します。秘密鍵、トークン、パスワード、Webhook、アカウント情報を設定ファイルに含めないでください。[記入例](distribution/connection.example.json)

## 開発

JDK 17、Android SDK 35、Gradle 8.11.1でビルドします。Android Studioからこのフォルダーを開けます。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

配布版は専用の署名鍵をGitHub Secretsで受け取り署名します。リポジトリに鍵を保存しません。個人名を含む証明書やデバッグ鍵は配布に使いません。署名鍵のバックアップを失うと同じアプリへの更新ができなくなります。
