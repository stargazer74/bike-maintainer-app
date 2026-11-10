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
