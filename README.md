# ZipBackUp

選んだAndroid端末内のフォルダを **AES-256で暗号化したZIP** にまとめ、本人のGoogle Driveへ定期保存する個人用アプリです。日本語UI、Poco F7 / Android 16を想定しています（実機検証は未実施）。

[初期設定・署名・APK公開の手順](docs/SETUP.md) · [実機テスト項目](docs/TESTING.md)

## 機能

- 写真・動画・文書など、システムのフォルダ選択画面で許可したローカルフォルダを複数保存
- AES-256 ZIP / Zip64、暗号化したまま端末の作業領域に保存
- Google Driveの `ZipBackUp` フォルダへ送信。権限はアプリが作成・許可されたファイルだけを扱う `drive.file`
- **モバイルデータ通信を許可**する設定（初期値OFF）。Wi-Fiのみの場合、従量制Wi-Fiも送信対象外
- 6時間・12時間・1日・1週間の間隔、充電中のみ（初期値ON）、手動実行
- 送信後にサイズとMD5を照合したZIPだけを成功扱い。最新7回分を残し、古い分をDriveのゴミ箱へ移動
- ZIP全体の認証検証後、選択した復元先に新規フォルダを作成して復元
- 実行通知・中止・直近20件の履歴
- GitHub Actionsでテスト・Lint・APKビルド。署名Secrets設定後、手動実行で署名付きAPKをGitHub Releasesに添付

## 制約とデータ保護

端末全体や他アプリ内部（LINEの内部DB、アプリ設定、Android/data等）の完全バックアップではありません。連絡先・SMS・通話履歴も対象外です。rootや全ファイルアクセス権限は使用しません。

- ファイルの内容を暗号化します。**ZIPのファイル名・フォルダ名・サイズは隠れません**。
- ZIPのパスワードを忘れると復元できません。長く推測されにくいものを別途保管してください。変更前のZIPには変更前のパスワードが必要です。
- 自動処理のため、パスワードをAndroid KeystoreのAES-GCM鍵で暗号化して端末内に保存します。ロック解除要求を必須にすると無人実行できないため、都度の生体認証は要求しません。
- アプリのクラウドバックアップ・端末移行を無効化しています。アプリ削除で設定と暗号化鍵は失われます。ZIP自体はパスワードがあれば別端末でも復元できます。
- 平文の元ファイルをアプリ内部にコピーしません。バックアップZIPの一時保存に、おおむね対象データ量に相当する空き容量が必要です。復元にはZIPのコピーと展開先の空き容量が必要です。
- 自動実行時刻はAndroidの省電力・ジョブ割当で遅れます。Android 16の長時間ジョブ制限があり、非常に大きいバックアップは分けて対象を選ぶなど実機での調整が必要です。
- 読み取り中にサイズ・更新日時の変化が分かったファイルがある場合は失敗とし、古いバックアップを整理しません。端末の原子的なスナップショットではありません。
- 送信は8MiBの分割リクエスト。中断後の再試行では完成済みZIPを再利用し、転送自体は最初からやり直します。完成済み送信は実行IDで検出します。未完了のDrive転送セッションは期限切れに任せます。
- 取得不能なフォルダ・ファイルを黙ってスキップしません。対象0件もエラーにします。
- 手動実行にも通信・充電・電池残量・空き容量の条件を適用します。設定変更は進行中バックアップを中止します。
- Driveのゴミ箱も容量を消費します。不要な世代はDriveで確認してからゴミ箱を空にしてください。異なるインストールのバックアップは自動整理の対象にしません。
- バックアップ時の一時ZIPは成功・最終失敗時に削除、中断で残ったものは7日後以降の次回処理時に削除します。

## ビルド

JDK 17、Gradle 8.13、Android SDK 36を使用します。

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

Gradle Wrapperは同梱していません。Actionsでは指定バージョンのGradleをセットアップします。ローカルではGradle 8.13をインストールしてください。

debug版は別アプリID `io.github.a14398138.zipbackup.debug` で、本番APKとは別にインストールされます。通常利用は署名済みRelease APKを使用してください。

## 検証

暗号化ZIPの往復、AES-256設定、誤パスワード、暗号文改変、非暗号化ZIP拒否、パストラバーサル、通信条件、保持世代、中止、展開サイズ上限をJUnitで検証します。Driveの実アカウント連携とPoco F7の省電力動作は、利用者による初期設定後の実機テストが必要です。

## 参考

- [Androidのストレージ制限](https://developer.android.com/training/data-storage/manage-all-files)
- [長時間のWorkManager処理](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [Google AuthorizationRequest](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
- [Driveのアップロード](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Zip4j](https://github.com/srikanth-lingala/zip4j)（Apache-2.0）
