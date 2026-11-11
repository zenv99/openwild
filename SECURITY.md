# Security & Privacy

OpenWild is built so that **nothing leaves your phone**.

| Guarantee | How it's enforced |
|---|---|
| No internet access at all | The app does not request the `INTERNET` permission, so Android blocks every network connection. CI fails the build if the permission ever appears. |
| No tracking | No analytics, ads, crash reporters, accounts, or Google Play Services. |
| No cloud backup | `allowBackup="false"` and backup/transfer rules exclude all app data. CI checks this. |
| Not debuggable | Published APKs are release builds (minified, `debuggable=false`). CI checks this. |
| Camera & photos | Used only on the device for identification; never saved outside the app's private cache. |
| Location | Optional and coarse. Only used on the device to suggest your region. Deny it and pick a region manually. |
| Sharing | "Share sighting", "Wrong ID?", "Feedback" and "Tip" only hand text/a photo to another app **when you tap them**. |
| New regions | Bundled in the APK, or imported from a file you choose (Regions → Import file). |

## Why Android shows a warning when installing

Any APK installed outside the Play Store from a developer Google doesn't know triggers a Play Protect warning. It's not a malware detection. It goes away once OpenWild is distributed through Google Play or F-Droid. You can also tap "Scan app" / "Send for scan" in the Play Protect dialog.

## Signing key

Builds are currently signed with the key in `android/app/openwild-dev.keystore`, which is public in this repo. **Only install OpenWild from this repo's Releases page.** Before public distribution, create a private release key and add it as GitHub secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`); the build will be switched to use it.

## Reporting a problem

Open an issue on this repo.
