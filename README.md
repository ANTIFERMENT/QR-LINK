# QR LINK 0.3.9 · Android OTA

Official QR LINK Android app, designed to build APK in GitHub Actions and update from public GitHub Releases.

## Deployment state

| Component | Status |
| --- | --- |
| GitHub repository | ✅ Public |
| OTA signing identity | ✅ Created; **private backup held outside GitHub** |
| Certificate pin | ✅ Embedded in QR LINK 0.3.9 source |
| Static OTA manifest URL | ✅ Embedded; can't be changed in Settings |
| GitHub Actions APK + manifest workflow | ✅ Configured |
| Python OTA utility tests | ✅ Passing |
| Android source ZIP | ⏳ Upload required: `QR_LINK_SOURCE.zip` |
| Secure signing bundle Secret | ⏳ One-time setup required |
| First signed APK / public Release | ⏳ Not yet created |

## Two actions to activate the first public release

1. Upload `QR_LINK_SOURCE.zip` (the **updated 0.3.9** source ZIP) to this repository's **root** using [Upload files](https://github.com/ANTIFERMENT/QR-LINK/upload/main). The workflow imports the ZIP automatically, no manual extraction needed.
2. Open [Actions Secrets](https://github.com/ANTIFERMENT/QR-LINK/settings/secrets/actions). Click **New repository secret**, enter Name: `QR_LINK_SIGNING_BUNDLE`. Value: the **entire single Base64 line** from `QR_LINK_SIGNING_BUNDLE_BASE64.txt` inside the separately supplied **PRIVATE** signing backup archive. Never upload this backup archive to GitHub.

Then visit [QR LINK · Android OTA workflow](https://github.com/ANTIFERMENT/QR-LINK/actions/workflows/ota-release.yml) and **Run workflow** if not already running. After a successful run, download the installable APK at [Releases](https://github.com/ANTIFERMENT/QR-LINK/releases).

## What the update client does

- Checks `https://github.com/ANTIFERMENT/QR-LINK/releases/latest/download/update.json` on launch (at most once every 24h), or via Settings → Check updates.
- Downloads `QR-LINK.apk` from this repository only.
- Confirms package ID `dev.xarkon.link`, incrementing `versionCode`, SHA-256, and the pinned release signing certificate.
- Prompts the Android system installer. The system may require confirmation.

**Important:** The old Code on the Go debug-signed APK and this newly generated release identity may be incompatible for an in-place upgrade. First installation of the new signature may require uninstalling the old APK, so back up user data. All future releases signed by the permanent identity are compatible.

## Signing security

- The **private key is NOT embedded into the APK**; doing so would let anyone forge a trusted update.
- Public certificate SHA-256 fingerprint pinned in `UpdateManager.java`:
  `2651D90F5C57F2D4A121E2D8B0899680343DC9E87E515ED5E100FC3D62C6D626`.
- Key never committed to GitHub. Signing identity remains in one encrypted GitHub Actions secret and an offline user backup.
- To rotate the signing key, one must explicitly change the application source, signing configuration and update strategy. There is no switch in the QR LINK UI.

Workflow: [.github/workflows/ota-release.yml](.github/workflows/ota-release.yml). Tool tests: [.github/workflows/ota-tools-tests.yml](.github/workflows/ota-tools-tests.yml).

**Current limit:** These tools were tested, but the 0.3.9 Android APK cannot be compiled or published until the source ZIP and signing Secret are present in GitHub.
