#!/usr/bin/env bash
# Run on your own trusted computer. Do not commit the output directory.
set -euo pipefail
umask 077
command -v keytool >/dev/null || { echo 'Java 17以降をインストールしてください。'; exit 1; }
if [[ -e signing-private ]]; then
  echo 'signing-private が存在します。既存の署名鍵を上書きしません。'; exit 1
fi
mkdir signing-private
read -r -s -p '署名鍵用の新しいパスワード（24文字以上、ZIP用とは別）: ' ZIPBACKUP_KEY_PASSWORD
printf '\n'
if [[ ${#ZIPBACKUP_KEY_PASSWORD} -lt 24 ]]; then echo '24文字以上にしてください。'; rmdir signing-private; exit 1; fi
read -r -s -p '確認のため再入力: ' ZIPBACKUP_CONFIRM
printf '\n'
if [[ "$ZIPBACKUP_KEY_PASSWORD" != "$ZIPBACKUP_CONFIRM" ]]; then echo '一致しません。'; rmdir signing-private; exit 1; fi
unset ZIPBACKUP_CONFIRM
export ZIPBACKUP_KEY_PASSWORD
keytool -genkeypair -keystore signing-private/release.p12 -storetype PKCS12 \
  -storepass:env ZIPBACKUP_KEY_PASSWORD -keypass:env ZIPBACKUP_KEY_PASSWORD \
  -alias zipbackup -keyalg RSA -keysize 3072 -validity 10000 \
  -dname 'CN=ZipBackUp, OU=Personal, O=ZipBackUp'
base64 < signing-private/release.p12 | tr -d '\r\n' > signing-private/KEYSTORE_BASE64.txt
keytool -list -v -keystore signing-private/release.p12 -storepass:env ZIPBACKUP_KEY_PASSWORD \
  -alias zipbackup > signing-private/signing-certificate.txt
unset ZIPBACKUP_KEY_PASSWORD
printf '%s\n' '作成しました。signing-privateフォルダとパスワードを安全に保管してください。' \
  'GitHub Secretsに KEYSTORE_BASE64（txtの内容）と KEYSTORE_PASSWORD（入力したパスワード）を登録してください。'
