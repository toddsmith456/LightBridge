#!/usr/bin/env bash
# CI self-test of the production signing path using a THROWAWAY key created on the runner.
# Runs the exact sign_apk.sh / verify_signature.sh used for real releases, then proves that
# a wrong pinned fingerprint and a debug-signed APK are both rejected.
# This key is NOT a release identity and its output is never published.
#
# usage: ephemeral_sign.sh unsigned.apk signed-out.apk [debug.apk]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
in="${1:?unsigned apk}"; out="${2:?output}"; debug_apk="${3:-}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
pass="$(openssl rand -hex 16)"
STORE_PASS="$pass" keytool -genkeypair -keystore "$tmp/t.p12" -storetype PKCS12 -alias ci-throwaway \
  -keyalg RSA -keysize 3072 -validity 2 -dname "CN=LightBridge CI throwaway" \
  -storepass:env STORE_PASS -keypass:env STORE_PASS >/dev/null 2>&1
fp="$(STORE_PASS="$pass" keytool -list -v -keystore "$tmp/t.p12" -alias ci-throwaway -storepass:env STORE_PASS \
      | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')"

LIGHTBRIDGE_KEYSTORE_BASE64="$(base64 < "$tmp/t.p12" | tr -d '\n')"
export LIGHTBRIDGE_KEYSTORE_BASE64
export LIGHTBRIDGE_KEYSTORE_PASSWORD="$pass" LIGHTBRIDGE_KEY_ALIAS=ci-throwaway LIGHTBRIDGE_CERT_SHA256="$fp"
"$here/sign_apk.sh" "$in" "$out"

echo "== negative: wrong pinned fingerprint must fail"
if "$here/verify_signature.sh" "$out" "$(printf '0%.0s' {1..64})" >/dev/null 2>&1; then
  echo "::error::verify_signature accepted a wrong fingerprint"; exit 1; fi
echo "== negative: missing secrets must fail (no debug fallback)"
if env -u LIGHTBRIDGE_KEYSTORE_BASE64 "$here/sign_apk.sh" "$in" "$tmp/x.apk" >/dev/null 2>&1; then
  echo "::error::sign_apk.sh succeeded without a keystore"; exit 1; fi
if [ -n "$debug_apk" ]; then
  echo "== negative: debug-signed APK must fail"
  if "$here/verify_signature.sh" "$debug_apk" "$fp" >/dev/null 2>&1; then
    echo "::error::verify_signature accepted a debug-signed APK"; exit 1; fi
fi
echo "ephemeral signing self-test OK (throwaway cert $fp)"
