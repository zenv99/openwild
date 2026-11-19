# Security & Privacy

OpenWild is built so that **nothing about you leaves your phone** unless you tap it. It goes online to download a region pack when you tap Download, to send a photo you chose to share, and — only if you turn on Online check and tap it — to ask Pl@ntNet about a plant.

| Guarantee | How it's enforced |
|---|---|
| Internet only for region downloads, photos you choose to send, and an optional Pl@ntNet check | `INTERNET` is used by `PackManager` to fetch region packs from GitHub (this repo's "packs" release). It refuses any host other than github.com / githubusercontent.com, HTTPS only (plain http blocked by the network security config, system certificates only), and checks each pack's SHA-256 before installing. Those requests carry no account, ID or location. `PhotoInbox` sends plant photos the user picked to the private OpenWild photo inbox on api.github.com only after they tap Send on a permission screen that lists exactly what goes (photos re-encoded so no GPS, time or camera data; plant name; stage; the name they want credited). `PlantNet` sends a photo to my-api.plantnet.org only when Online check is on and the person taps Check with Pl@ntNet. The key is one the person types, or a private build secret (`OW_PLANTNET_API_KEY` or `-PplantnetApiKey`); neither is committed. CI fails the build if any other permission appears, if plain http is allowed, if network code appears outside `PackManager`/`PhotoInbox`/`PlantNet`, if `PhotoInbox` names any host other than api.github.com, or if `PlantNet` names any host other than my-api.plantnet.org. |
| Works offline | Identification, search, safety info and photos all run from the downloaded pack with no signal. |
| No tracking | No analytics, ads, crash reporters, accounts, or Google Play Services. |
| No cloud backup | `allowBackup="false"` and backup/transfer rules exclude all app data. CI checks this. |
| Not debuggable | Published APKs are release builds (minified, `debuggable=false`). CI checks this. |
| Camera & photos | Used only on the device for identification; never saved outside the app's private cache. |
| Location | Optional and coarse. Only used on the device to suggest your region. Deny it and pick a region manually. |
| Sharing | "Share sighting", "Wrong ID?", "Feedback" and "Tip" only hand text/a photo to another app **when you tap them**. |
| New regions | Downloaded in the Packs tab, or imported from a file you choose (Packs → Import file). |

## Why Android shows a warning when installing

Any APK installed outside the Play Store from a developer Google doesn't know triggers a Play Protect warning. It's not a malware detection. It goes away once OpenWild is distributed through Google Play or F-Droid. You can also tap "Scan app" / "Send for scan" in the Play Protect dialog.

## Signing key

Builds are currently signed with the key in `android/app/openwild-dev.keystore`, which is public in this repo. **Only install OpenWild from this repo's Releases page.** Before public distribution, create a private release key and add it as GitHub secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`); the build will be switched to use it.

## Reporting a problem

Open an issue on this repo.

## Online check (opt-in)

The online check is off until the user taps "Check online" and agrees in a dialog. It then sends only the
photos being identified (re-encoded JPEG: no location, time or camera data), the region and the offline answer
to the private OpenWild inbox on api.github.com (the same single host as photo sharing). A GitHub Action in
that private repository identifies them and writes a result file that the phone reads back. The photos are
deleted after the check unless the user ticked "Let OpenWild keep these photos". Results are removed after two
days. The phone never contacts any other server; the cloud check itself fetches reference photos from
iNaturalist, never sending the user's photos there. "Turn off online check" withdraws the consent.

A separate Online check in the brain section uses the Pl@ntNet identify API (plants only, not mushrooms). It is off by default. Turning it on does not send anything. A photo is sent only when the person taps Check with Pl@ntNet on a result, and only if the phone has a connection. The free plan allows 500 identifications a day per key, including non-commercial use. The public app does not contain a key: the person pastes their own key from my.plantnet.org, and it stays in app-private storage. A private build may set `OW_PLANTNET_API_KEY` or `-PplantnetApiKey`; a key typed on the phone replaces it. Results are shown next to the offline answer with a Pl@ntNet credit. They are not a reason to eat anything. iNaturalist's computer-vision API is not public, so it is not used. No fungi identification service had terms that allow this app to call it.
