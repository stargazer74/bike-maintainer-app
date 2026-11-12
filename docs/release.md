# Releasing the app

Releases are signed **locally**: the signing key never leaves the maintainer's machine and is not stored at GitHub.
CI only builds an unsigned release (R8 check). Where the key and its backup are kept is documented in the private
wiki, not here.

## One key for all channels

The signing key is the app's identity. Android only installs an update that is signed with the same key, so:

- **Losing the key** means no more updates – every user would have to uninstall (losing their settings) and reinstall.
- **Leaking the key** lets others publish "updates" that Android accepts.
- The same key signs the GitHub APK and, later, the Google Play version (uploaded to Play App Signing as "use my
  own key"), so users can switch between the channels with a normal update.
- `applicationId` (`de.bikemaintainer.app`) never changes.

## Create the key (once)

```bash
export BM_KEYSTORE_FILE=/path/outside/the/repo/bike-maintainer-release.p12   # private directory, chmod 700
mkdir -p "$(dirname "$BM_KEYSTORE_FILE")" && chmod 700 "$(dirname "$BM_KEYSTORE_FILE")"   # keytool does not create it
keytool -genkeypair -v -storetype PKCS12 -keystore "$BM_KEYSTORE_FILE" \
  -alias bike-maintainer -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Bike Maintainer"
chmod 600 "$BM_KEYSTORE_FILE"
```

- `-dname` ends up in every APK and can be read by anyone: no personal name, address or email.
- 100 years validity: an expired key is as bad as a lost one.
- Password into the password manager. Back up the keystore file and the password **separately** (e.g. the file
  encrypted on a USB stick).

## Versions

Tags are `vX.Y.Z` (own sequence of the app, starting at `v1.0.0`). The build derives:

- `versionName` = `X.Y.Z`
- `versionCode` = `X·1 000 000 + Y·1 000 + Z` (minor and patch up to 999), so it rises strictly across all channels.

Builds without a version (`./gradlew assembleRelease`) are `0.0.0-dev` with `versionCode` 1.

## Make a release

1. On `main` with a clean working tree, create the tag: `git tag v1.2.3 && git push origin v1.2.3`.
   CI builds the unsigned release for this tag as a check.
2. Build and sign locally:

   ```bash
   BM_KEYSTORE_FILE=/path/to/bike-maintainer-release.p12 scripts/release.sh
   ```

   The script asks for the keystore password (or takes `BM_KEYSTORE_PASSWORD`; `BM_KEY_ALIAS` defaults to
   `bike-maintainer`), refuses a dirty tree or a missing tag, verifies the signatures and prints the certificate's
   SHA-256 fingerprint. Result in `dist/` (git-ignored):
   - `bike-maintainer-1.2.3.apk` and `bike-maintainer-1.2.3.apk.sha256` → attach to the GitHub release
   - `bike-maintainer-1.2.3.aab` → for Google Play; keep it
3. Create the GitHub release for the tag, attach the APK and the checksum file. The release notes name the
   signing certificate fingerprint.
4. First release only: enter the fingerprint in the README (section "Installation").

## Checks before the first public release

- Install the signed APK on a device and go through setup, login (portal and household), vehicle list and detail,
  mileage, log maintenance and a forced reminder run.
- Install the next version over it: it must update without losing data (`adb install -r`), and installing an older
  version must be refused (`INSTALL_FAILED_VERSION_DOWNGRADE`).

## Google Play: Play App Signing (once, before the first upload)

Google Play re-signs every app it delivers. So that the Play version carries the same signature as the GitHub APK,
Play must sign with **our** key – never with a key Google generates. That choice cannot be undone later.

1. Play Console → the app → *Test and release* → *App integrity* → *Play app signing* (the first release asks for it
   as well) → change the signing key → **"Export and upload a key from Java keystore"**. Do not pick "Use a
   Google-generated key".
2. Download the two files offered there, `pepk.jar` and `encryption_public_key.pem`, into a fresh directory outside
   the repo. The public key belongs to the developer account; download it again instead of reusing an old copy.
3. Export the key, encrypted so that only Google can read it (current JDK, 17 or newer):

   ```bash
   work=/path/to/the/download/directory   # holds pepk.jar and encryption_public_key.pem
   java -jar "$work/pepk.jar" \
     --keystore="$BM_KEYSTORE_FILE" --alias=bike-maintainer \
     --output="$work/bike-maintainer-signing-key.zip" \
     --include-cert --rsa-aes-encryption \
     --encryption-key-path="$work/encryption_public_key.pem"
   ```

   pepk asks for the keystore and the key password (both the same for our PKCS12 keystore).
4. Upload `bike-maintainer-signing-key.zip` on the same page. Skip the optional step "upload key certificate": then
   the release key is also the upload key, and `scripts/release.sh` already signs the AAB with it.
5. Remove the working directory (`rm -rf "$work"`); the ZIP is of no further use.
6. Check: *App integrity* shows the SHA-256 of the app signing key and of the upload key certificate. Both must equal
   the fingerprint that `scripts/release.sh` prints (and the README names).

**Upload key:** Google recommends a separate upload key, which Google can replace if it leaks, while the app signing
key stays safe. We do without one for now: the release key never leaves the maintainer's machine, and since it also
signs the GitHub APK, a leak would be fatal anyway. A separate upload key can be added later (via Play support "reset
upload key"); `scripts/release.sh` would then sign the AAB with that second keystore.

**Automatic protection:** keep it switched off (Play Console offers it when the app is created and under *App
integrity*). It adds code to the app that checks via Google Play where the app was installed from – a call to Google
services, and a Play build that differs from the GitHub and F-Droid ones.

## Store listing (Google Play, F-Droid)

The texts of the store entry live in `fastlane/metadata/android/<locale>/` (`de-DE`, `en-US`) – the layout both
Google Play and F-Droid read, so there is only one copy:

| File | Limit | Content |
|---|---|---|
| `title.txt` | 30 | App name |
| `short_description.txt` | 80 | One line under the name |
| `full_description.txt` | 4000 | Plain text; `•` for lists (no Markdown) |
| `changelogs/<versionCode>.txt` | 500 | "What's new" of that release, e.g. `1001000.txt` for 1.1.0 |

Rules for the texts:

- **No subscription, prices or purchase** – neither in the app nor in its store entry (Google Play payments policy);
  the subscription only exists on the website.
- No superlatives, rankings or calls like "download now" (Play metadata policy); no personal data.
- Describe only what the released version can do; add the changelog of each release before uploading it.
- Keep the German and English versions in step.

In Play Console, copy the texts into the store entry of each language (or upload them with fastlane `supply`).
