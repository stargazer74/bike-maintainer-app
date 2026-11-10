#!/usr/bin/env bash
# Builds a signed release (APK for GitHub Releases, AAB for Google Play) from the tag on HEAD.
#
# Usage: BM_KEYSTORE_FILE=/path/to/release.p12 scripts/release.sh
#   Asks for the keystore password unless BM_KEYSTORE_PASSWORD is set; BM_KEY_ALIAS defaults to bike-maintainer.
#   The password is only passed to Gradle via the environment, never written to disk.
# Result in dist/: bike-maintainer-<version>.apk, .aab and .apk.sha256. See docs/release.md.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() { echo "release.sh: $*" >&2; exit 1; }

[[ -z "$(git status --porcelain)" ]] || fail "working tree is not clean – commit or stash first"
tag=$(git describe --tags --exact-match --match 'v*' HEAD 2>/dev/null) || fail "no tag vX.Y.Z on HEAD"
[[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || fail "tag '$tag' is not vX.Y.Z"
version="${BASH_REMATCH[1]}"

[[ -n "${BM_KEYSTORE_FILE:-}" ]] || fail "set BM_KEYSTORE_FILE to the release keystore"
[[ -r "$BM_KEYSTORE_FILE" ]] || fail "keystore not readable: $BM_KEYSTORE_FILE"
if [[ -z "${BM_KEYSTORE_PASSWORD:-}" ]]; then
    read -r -s -p "Keystore password: " BM_KEYSTORE_PASSWORD
    echo
fi
export BM_KEYSTORE_FILE BM_KEYSTORE_PASSWORD

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f local.properties ]]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties)
fi
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[[ -x "$apksigner" ]] || fail "apksigner not found – set ANDROID_HOME"

echo "Building $tag …"
./gradlew --no-daemon -q clean assembleRelease bundleRelease -Pbm.version="$version"
./gradlew --stop > /dev/null 2>&1 || true

apk=app/build/outputs/apk/release/app-release.apk
aab=app/build/outputs/bundle/release/app-release.aab
[[ -f "$apk" ]] || fail "no signed APK – is the keystore password correct?"

certs=$("$apksigner" verify --print-certs "$apk" 2> /dev/null) || fail "apksigner verify failed"
fingerprint=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$certs")
# jarsigner exits 0 for unsigned files as well, so check its verdict.
jarsigner -verify "$aab" | grep -q '^jar verified' || fail "AAB is not signed"

mkdir -p dist
name="bike-maintainer-$version"
cp "$apk" "dist/$name.apk"
cp "$aab" "dist/$name.aab"
(cd dist && sha256sum "$name.apk" > "$name.apk.sha256")

echo
echo "Release $tag built:"
echo "  dist/$name.apk         → attach to the GitHub release"
echo "  dist/$name.apk.sha256  → attach to the GitHub release"
echo "  dist/$name.aab         → Google Play (keep)"
echo "Signing certificate SHA-256: $fingerprint"
