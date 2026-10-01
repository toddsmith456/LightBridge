#!/usr/bin/env bash
# Sign an UNSIGNED, zipaligned release APK with the private release key and verify the result.
#
# Secrets are read from the environment only (never argv, never logged):
#   LIGHTBRIDGE_KEYSTORE_BASE64   base64 of the PKCS12/JKS keystore
#   LIGHTBRIDGE_KEYSTORE_PASSWORD keystore password
#   LIGHTBRIDGE_KEY_ALIAS         key alias
#   LIGHTBRIDGE_KEY_PASSWORD      key password (defaults to the keystore password)
#   LIGHTBRIDGE_CERT_SHA256       expected release-certificate SHA-256 (public, pinned)
#
# usage: sign_apk.sh unsigned.apk signed.apk
set -euo pipefail

in="${1:?unsigned apk}"; out="${2:?output apk}"
for v in LIGHTBRIDGE_KEYSTORE_BASE64 LIGHTBRIDGE_KEYSTORE_PASSWORD LIGHTBRIDGE_KEY_ALIAS LIGHTBRIDGE_CERT_SHA256; do
  if [ -z "${!v:-}" ]; then
    echo "::error::$v is not set. Production signing is not configured; refusing to fall back to debug signing. See docs/RELEASING.md."
    exit 1
  fi
done
export LIGHTBRIDGE_KEY_PASSWORD="${LIGHTBRIDGE_KEY_PASSWORD:-$LIGHTBRIDGE_KEYSTORE_PASSWORD}"

bt="$(ls -d "${ANDROID_HOME:?}"/build-tools/* | sort -V | tail -1)"
tmp="$(mktemp -d)"; ks="$tmp/release.keystore"
trap 'rm -rf "$tmp"' EXIT
umask 077
printf '%s' "$LIGHTBRIDGE_KEYSTORE_BASE64" | base64 -d > "$ks"

"$bt/zipalign" -c -P 16 4 "$in" || { echo "::error::input APK is not 16 KB/4-byte aligned"; exit 1; }
"$bt/apksigner" sign \
  --ks "$ks" --ks-key-alias "$LIGHTBRIDGE_KEY_ALIAS" \
  --ks-pass env:LIGHTBRIDGE_KEYSTORE_PASSWORD --key-pass env:LIGHTBRIDGE_KEY_PASSWORD \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$out" "$in"
rm -rf "$tmp"; trap - EXIT

"$(dirname "$0")/verify_signature.sh" "$out" "$LIGHTBRIDGE_CERT_SHA256"
