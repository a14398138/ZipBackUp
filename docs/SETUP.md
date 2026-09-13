# 初期設定ガイド

初回だけ **APKの署名設定** と **Google Cloudへのアプリ登録** が必要です。Googleアカウントのパスワード、署名鍵、ZIPパスワードをチャットや公開リポジトリに貼らないでください。

## 1. APKの署名鍵を作る

署名鍵は「次のAPKも同じ作者の更新である」とAndroidに示すものです。更新時も同じ鍵を使います。APKビルドのたびに生成し直すことはしません。

Java 17以降が入った自分のPCのターミナルで実行します（WindowsはGit Bash等を使用）。PCがない場合は、この手順を実行できる環境について相談してください。

```sh
git clone https://github.com/a14398138/ZipBackUp.git
cd ZipBackUp
bash scripts/create-signing-key.sh
```

24文字以上の署名鍵用パスワードを入力します。ZIP暗号化用とは別のものにしてください。作成された `signing-private` フォルダと入力したパスワードを、自分の安全な場所に保管してください。

## 2. GitHub Secretsへ登録する

[リポジトリのActions Secrets設定](https://github.com/a14398138/ZipBackUp/settings/secrets/actions) を開き、`New repository secret` で次の2つを作ります。

| Name | Secretに入れるもの |
|---|---|
| `KEYSTORE_BASE64` | `signing-private/KEYSTORE_BASE64.txt` の内容全体 |
| `KEYSTORE_PASSWORD` | 手順1で入力した署名鍵用パスワード |

APKやReleaseへ秘密鍵を添付しないでください。公開してよいのは証明書の指紋（SHA-1など）です。

## 3. APKをActionsで作りReleaseへ公開する

1. [Android build and release](https://github.com/a14398138/ZipBackUp/actions/workflows/android.yml) を開きます。
2. `Run workflow` を選び、ブランチ `main`、`release` を有効にして実行します。
3. `verify` と `release` の両方が成功するまで待ちます。
4. [Releases](https://github.com/a14398138/ZipBackUp/releases) に `ZipBackUp.apk`、`signing-certificate.txt`、`SHA256SUMS.txt` が公開されます。

初版は実機確認前のためPre-releaseです。検証用debug APKを本番用として公開しません。署名Secretsがない場合、releaseジョブは説明を出して停止します。

`signing-certificate.txt` の `Signer #1 certificate SHA-1 digest` の値を次の手順で使います。コロンなしの40桁で出力される場合、Google画面が要求する形式に合わせて2桁ごとにコロンを入れます。署名鍵作成時の `signing-private/signing-certificate.txt` にもSHA-1があります。

## 4. Google Cloudへ登録する

このアプリはAndroidのGoogle Play開発者サービスから認可を受けます。自前サーバー、サービスアカウントの秘密鍵、Web用クライアントシークレットは不要です。

1. [Google Cloud Console](https://console.cloud.google.com/) をバックアップ先のGoogleアカウントで開きます。
2. プロジェクト選択から新しいプロジェクトを作成します。表示名は `ZipBackUp` で構いません。
3. 「APIとサービス」→「ライブラリ」で **Google Drive API** を検索し、有効にします。
4. 「Google Auth Platform」（画面によっては「OAuth同意画面」）で初期設定を行います。アプリ名 `ZipBackUp`、サポートメールと連絡先は自分のメールです。
5. 個人のGmailで利用する場合、対象は **External / 外部**。まず **Testing / テスト中** とし、テストユーザーに自分のGoogleアカウントを追加します。「外部」を選んでも自動的に一般公開されるわけではありません。
6. 「Data Access / データアクセス」で次のスコープを追加します：

   `https://www.googleapis.com/auth/drive.file`

7. 「Clients / クライアント」→「クライアントを作成」で種類を **Android** にします。
8. 以下を入力して作成します。

| 項目 | 値 |
|---|---|
| 名前 | `ZipBackUp Android` |
| パッケージ名 | `io.github.a14398138.zipbackup` |
| SHA-1証明書フィンガープリント | 手順3のAPK署名証明書のSHA-1 |

この認可方式では、作成されたクライアントIDをソースコードやAPKに追記する必要はありません。Androidのパッケージ名と署名の組み合わせで識別されます。Google Cloud側の設定反映には時間がかかる場合があります。

Testing状態では認可の有効期間などに制約があるため、長期の無人運用で再接続が必要になる可能性があります。まず接続確認を行い、継続運用時はGoogleの画面と最新のポリシーに従って公開ステータスを検討してください。公開リポジトリであることと、Google OAuthの公開ステータスは別です。

## 5. Poco F7へインストールして設定する

1. Releaseから `ZipBackUp.apk` をダウンロードしてインストールします。求められたらダウンロードに使ったブラウザー等へ「不明なアプリのインストール」を許可します。
2. アプリを開き、`Google Driveに接続` から登録したGoogleアカウントを選び、許可します。
3. ZIP用パスワードを設定し、別の安全な場所にも控えます。
4. フォルダを追加します。まず小さなテスト用フォルダに文書や写真を数件入れて選びます。Androidの制限でルートやDownloadそのものを選べない場合は、その下のフォルダを選んでください。
5. 通知を許可します。`モバイルデータ通信を許可` は必要に応じてONにします。
6. `充電中のみ実行` をONにしたままの場合は充電器を接続します。
7. `今すぐバックアップ` を押し、完了通知とDrive上のZIPを確認します。
8. ZIPをダウンロードし、別のフォルダへ復元して内容を確認します。
9. 成功後、`自動バックアップ` をONにします。

Poco / HyperOSの設定で、アプリのバックグラウンド動作、バッテリー制限、バックグラウンド自動起動を確認してください。実際の項目名はOSバージョンで異なります。アプリ内の「端末のアプリ設定」から進めます。強制停止すると、アプリを再度開くまでバックグラウンド処理が止まります。

## うまく動かない場合

- Google接続：Drive API有効化、テストユーザー、パッケージ名、本番APKのSHA-1を確認。debug版のSHA-1と混同しないでください。
- 実行待ち：Wi-Fi、充電、電池残量、空き容量、自動実行のON/OFFを確認。
- Drive容量不足：不要なファイルやゴミ箱を確認。ZIPの保存失敗時には古いバックアップを自動整理しません。
- 復元不可：ZIP作成当時のパスワードを使用。標準ZIP解凍アプリの中にはAES ZIPに対応しないものがあります。
- 非常に大きいバックアップ：Android 16の実行時間・割当制限の影響があり、まず小さな対象で実機確認してください。

参考：[GoogleのAndroidクライアント認証](https://developers.google.com/android/guides/client-auth)、[Google OAuth概要](https://developers.google.com/identity/protocols/oauth2)、[長時間ジョブの制約](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
