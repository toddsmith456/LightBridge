#!/usr/bin/env bash
# Verify an APK is properly signed by the pinned production certificate — and not by a debug key.
# usage: verify_signature.sh app.apk EXPECTED_CERT_SHA256
set -euo pipefail
apk="${1:?apk}"; expected="$(echo "${2:?expected sha256}" | tr -d ': \n' | tr 'A-F' 'a-f')"
bt="$(ls -d "${ANDROID_HOME:?}"/build-tools/* | sort -V | tail -1)"

report="$("$bt/apksigner" verify --verbose --print-certs --min-sdk-version 24 "$apk")"
echo "$report"
grep -q 'Verifies' <<<"$report" || { echo "::error::apksigner did not verify the APK"; exit 1; }
grep -q 'v2 scheme (APK Signature Scheme v2): true' <<<"$report" || { echo "::error::v2 signature missing"; exit 1; }
grep -q 'Number of signers: 1' <<<"$report" || { echo "::error::expected exactly one signer"; exit 1; }
if grep -qiE 'Android Debug|CN=Android' <<<"$report"; then
  echo "::error::APK is signed with an Android debug certificate"; exit 1
fi
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$report" | tr -d ': ' | tr 'A-F' 'a-f')"
if [ "$actual" != "$expected" ]; then
  echo "::error::certificate SHA-256 mismatch. expected=$expected actual=$actual"; exit 1
fi
"$bt/zipalign" -c -P 16 4 "$apk"
echo "signature OK: cert SHA-256 $actual"
