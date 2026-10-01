#!/usr/bin/env bash
# One-time, LOCAL setup of LightBridge's production signing identity.
#
# Run this on your own machine (not in chat, not in CI). It:
#   1. creates a new release keystore (or imports one you already own) in a private directory,
#   2. stores the key material as GitHub *environment secrets* (environment: release) via stdin,
#      so nothing is echoed, logged, or placed on a command line,
#   3. pins the public certificate fingerprint as a repository variable so the release
#      workflow can prove the APK was signed by THIS key.
#
# Requirements: keytool (JDK 17+), openssl, gh (authenticated with admin rights on the repo).
#
#   tools/setup-release-signing.sh                        # generate a new key
#   tools/setup-release-signing.sh --existing my.jks      # import an existing keystore
#   tools/setup-release-signing.sh -R owner/repo --dir ~/.lightbridge-signing
#
# BACK UP the printed directory OFFLINE (e.g. encrypted drive + password manager). If you lose
# this key you can never publish an update that installs over existing LightBridge installs.
set -euo pipefail

repo="toddsmith456/LightBridge"; dir="$HOME/.lightbridge-signing"; existing=""; env_name="release"
alias_name="lightbridge-release"
while [ $# -gt 0 ]; do
  case "$1" in
    -R) repo="$2"; shift 2;;
    --dir) dir="$2"; shift 2;;
    --existing) existing="$2"; shift 2;;
    --alias) alias_name="$2"; shift 2;;
    -h|--help) sed -n '2,22p' "$0"; exit 0;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
for c in keytool openssl gh base64; do command -v "$c" >/dev/null || { echo "missing: $c" >&2; exit 1; }; done
gh auth status >/dev/null 2>&1 || { echo "run 'gh auth login' first" >&2; exit 1; }
git_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
case "$(cd "$dir" 2>/dev/null && pwd || echo "$dir")" in
  "$git_root"/*) echo "refusing to keep a keystore inside the git work tree" >&2; exit 1;; esac

umask 077
mkdir -p "$dir"; chmod 700 "$dir"
ks="$dir/lightbridge-release.p12"

if [ -n "$existing" ]; then
  [ -f "$existing" ] || { echo "no such file: $existing" >&2; exit 1; }
  read -r -s -p "Keystore password: " store_pass; echo
  read -r -s -p "Key password (blank = same as keystore): " key_pass; echo
  key_pass="${key_pass:-$store_pass}"
  read -r -p "Key alias: " alias_name
  cp "$existing" "$ks"
else
  [ ! -e "$ks" ] || { echo "$ks already exists; refusing to overwrite. Use --existing or --dir." >&2; exit 1; }
  read -r -p "Certificate owner name (CN), e.g. 'Todd Smith': " cn
  store_pass="$(openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32)"
  key_pass="$store_pass"   # PKCS12 uses one password for store and key
  STORE_PASS="$store_pass" keytool -genkeypair -keystore "$ks" -storetype PKCS12 -alias "$alias_name" \
    -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=${cn:-LightBridge}, O=LightBridge" \
    -storepass:env STORE_PASS -keypass:env STORE_PASS
  printf 'keystore: %s\nalias: %s\npassword: %s\n' "$ks" "$alias_name" "$store_pass" > "$dir/PASSWORDS-BACK-UP-OFFLINE.txt"
fi

fp="$(STORE_PASS="$store_pass" keytool -list -v -keystore "$ks" -alias "$alias_name" -storepass:env STORE_PASS \
      | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')"
[ "${#fp}" -eq 64 ] || { echo "could not read certificate fingerprint (wrong password/alias?)" >&2; exit 1; }

echo "Creating GitHub environment '$env_name' on $repo (add required reviewers in Settings → Environments)…"
gh api -X PUT "repos/$repo/environments/$env_name" >/dev/null

b64() { base64 < "$1" | tr -d '\n'; }
b64 "$ks"                  | gh secret set LIGHTBRIDGE_KEYSTORE_BASE64   --env "$env_name" -R "$repo"
printf '%s' "$store_pass"  | gh secret set LIGHTBRIDGE_KEYSTORE_PASSWORD --env "$env_name" -R "$repo"
printf '%s' "$key_pass"    | gh secret set LIGHTBRIDGE_KEY_PASSWORD      --env "$env_name" -R "$repo"
printf '%s' "$alias_name"  | gh secret set LIGHTBRIDGE_KEY_ALIAS         --env "$env_name" -R "$repo"
gh variable set LIGHTBRIDGE_CERT_SHA256 --body "$fp" -R "$repo"

echo
echo "Done. Release certificate SHA-256 (public, now pinned in repo variables):"
echo "  $fp"
echo "Back up $dir OFFLINE now. Then tag a release:  git tag v0.1.0 && git push origin v0.1.0"
