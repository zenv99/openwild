# Profiles

Optional profiles for people who want their name, title, and affiliation on plant photos and reviews, and for students and researchers who want a verified school email to count for more in identification scoring. Identification, safety text, and region packs keep working with no account at all.

Online profiles are **off** unless a build sets `profilesOnline=true` and a profiles API URL. This repository does not deploy the worker, create cloud resources, send email, or store secrets. Deploy steps are at the end. Do not run them until the owner approves.

## What we reused, and what we did not copy

The behavior follows public projects. The code in this repository is original, so licenses stay compatible with Apache-2.0.

| Project | What we followed | License note |
|---|---|---|
| [iNaturalist](https://www.inaturalist.org/) API and the [iNaturalist Android](https://github.com/inaturalist/iNaturalistAndroid) app (MIT) | A public person (name, icon) separate from a private login. Photos carry their own license and an attribution string. `geoprivacy` defaults away from exact coordinates (`open`, `obscured`, or `private`). | No iNaturalist source is copied. We do not call iNaturalist's private computer-vision API. |
| [Pl@ntNet](https://docs.plantnet.org/en/reference/data-and-image-licensing/) | Show the contributor's name, the license, and the project on every reused photo. Pl@ntNet observation data is generally CC BY and images are generally CC BY-SA, chosen per observation. | `plantnet/my.plantnet` is GPL-3. None of that code is included. OpenWild's own submissions default to **CC BY 4.0**, with **CC0** and **CC BY-SA 4.0** as the other choices. Non-commercial licenses are not offered here: a photo the person asks us to show must stay openly reusable. Reference photos already in region packs keep their original licenses, including NC where the pack already has them. |
| [OpenStreetMap accounts](https://wiki.openstreetmap.org/wiki/OpenStreetMap_account) | The name other people see is a display name. The email is the login and is never published. People may use a real name or a pseudonym. | The OSM website is GPL. None of that code is included. We do not use Gravatar: OSM's privacy policy notes that a hash of an email can track a person across sites. Profile photos are files the person picks, stored by us. |
| [Hipo/university-domains-list](https://github.com/Hipo/university-domains-list) (MIT) | A snapshot of university domains for "is this mailbox a school?". Hipo lists the registered domain (`mit.edu`), not a department host, so `student@cs.mit.edu` still matches. | Domain strings and the MIT license are in `server/data/`. See `server/data/SOURCES.md`. |
| [JetBrains/swot](https://github.com/JetBrains/swot) (MIT) | Parent domains cover subdomains. Domains in `abused.txt` are not trusted even when they look like `.edu`. | We vendor `abused.txt` only, not the swot tree. License: `server/data/LICENSE-swot.txt`. |

Sign-in is email only: a one-time code (OTP) the person types, which is also what a magic link would hand to the app. There is no password and no social login.

## Data model

### Profile

Stored on the phone in `profile.db` (app-private, backup off, same policy as the rest of the app). The server has the same fields once online profiles are deployed.

| Field | Rules |
|---|---|
| Display name | Optional. Up to 60 characters. Not an email. |
| Photo | Optional JPEG the app re-encodes (no EXIF). |
| Title | Optional. Up to 80 characters. Example: student, botanist. |
| Affiliation | Optional. Up to 120 characters. School or organisation the person typed. The domain list does not fill this in. |
| Visibility | `public` or `anonymous`. |
| Email | Optional on the phone. Required only to sign in online. Never copied onto a photo, a review, or a public API response. |
| Verified academic | Boolean. Set only after the person proves they can read a school mailbox. Choosing a school-looking address does not set it. |
| Verified domain | The matched school domain, such as `mit.edu`. Shown on a public profile only. Hidden when visibility is anonymous. |
| Default license | `cc-by` (CC BY 4.0), `cc0`, or `cc-by-sa`. Default `cc-by`. |

There is one profile on the phone. Deleting it removes the row, the photo file, and the local submission queue.

### Contributions

Each review, photo, and identification note is its own row. The credit and the visibility are copied onto the row at submit time, so a later rename does not rewrite history until the person edits that row.

| Field | Rules |
|---|---|
| Kind | `review`, `photo`, or `id_feedback`. |
| Species and region | The scientific name and the region pack id. |
| Body and rating | Review text (up to 4,000 characters) and 1–5 stars, or the identification note. |
| Visibility | `public` or `anonymous`, chosen for this submission. Anonymous forces the credit to `Anonymous`. |
| License | One of the three codes above. Photos and identification photos record it. Reviews use the profile default when they are shared. |
| Credit | `Anonymous`, or the display name. Format for photos: `Photo: NAME · CC BY 4.0` (or CC0 / CC BY-SA 4.0). Format for reviews: `Review by NAME`. |
| Status | `queued` on the phone. On the server: `pending` (new account's photos, hidden), `public`, or `hidden`. |
| Location | Empty unless the person opts in. Then one coarse cell only (see Privacy). |
| Trust weight | Number used when combining identification feedback. See Trust. |

The phone queue does not store the email. Sync, when it is turned on, sends the queue plus a session token. The token is how the server knows the account. The body of the contribution still has no email.

### Server tables

`server/schema.sql`, for Cloudflare D1:

- `account` — id, email, domain, created time, verified-academic flag. Email is unique. No password column.
- `session` — hash of the bearer token, not the token.
- `otp` — hash of the one-time code, salt, expiry, attempt count. The code itself is not stored.
- `profile` — the public fields, plus verified domain.
- `contribution` — the fields above. `account_id` becomes null when the account is deleted and the row was already public.
- `report` — contribution id, reason, time. No reporter email.

R2 keys: `avatars/<account id>.jpg`, `photos/<contribution id>.jpg`. The worker serves them. The bucket is not given a public website.

## Sign-in

Email magic link / OTP only.

1. `POST /v1/auth/start` with `{ "email" }`. The worker checks the address, stores a salted hash of an 8-digit code for 15 minutes, and asks the mailer to send it. Five starts per address per hour.
2. The message contains the code. A link, if the mailer adds one, only opens the app. The app then `POST`s the code. The code is not put in a query string, so it does not leak via server logs or a browser `Referer`.
3. `POST /v1/auth/verify` with `{ "email", "code" }`. Five wrong tries burn the code. Success creates the account if needed, sets the session, and returns the token once. Only the hash is stored.
4. Later calls send `Authorization: Bearer <token>`.

If no mailer is configured the start call returns **503** `email_not_configured` and deletes the code it just stored. Nothing is emailed. Tests inject a fake mailer. Production must not set a test mode that echoes the code in the HTTP response. This worker never puts the code in the response.

A mailbox that is not academic can still sign in. It simply does not get the verified flag.

There is no password reset because there is no password. There is no Google, Apple, or Facebook login.

## Academic verification

A verified badge means "this person proved they can read mail at a school domain", not "this person is an expert" and not "this plant is safe to eat".

An address counts as academic when all of these hold:

1. It is a single mailbox (`local@domain`), not a free-mail host (`gmail.com`, `outlook.com`, `yahoo.com`, and the short denylist in code).
2. The domain, or a parent (`cs.mit.edu` → `mit.edu`), is **not** on the swot abused snapshot.
3. A parent is on the Hipo snapshot, **or** the domain matches a school suffix:
   - the final label is `edu` (`mit.edu`, `cs.mit.edu`)
   - or the label before a two-letter country code is `edu` or `ac` (`ox.ac.uk`, `ui.ac.id`, `uni.edu.au`)

`example.ac` is not academic. `.ac` alone is a country code, not a school.

The badge and `verified_domain` are set only in step 3 of sign-in, when the code is accepted and the check passes. The phone can say "this domain is on the school list" before that. That sentence is not a badge. The on-phone check uses the same text files, copied into the APK at build time from `server/data/`.

The badge is shown only when visibility is public. An anonymous profile can still belong to a verified mailbox; other people are not told that.

## Privacy

- The app works with no profile and no network, as it does today.
- Default build: `PROFILES_ONLINE` is false and the API URL is empty. `ProfileSync` returns immediately and does not open a connection.
- No location is attached to a profile or a submission unless the person turns on "share approximate place" for that submission. The existing wrong-ID sheet already works this way.
- Photos are re-encoded to a new JPEG before they are stored or sent. That drops EXIF, including GPS, time, and camera identifiers. The same path already serves the GitHub photo inbox.
- Opt-in location is one 0.1 degree cell (about 11 km), the same rounding the wrong-ID report already uses (`CoarseLocation`). The server rounds again and drops coordinates when the flag is off. This is coarser than a street. It is not iNaturalist's random obscured box; it is a fixed cell, and it is off unless chosen.
- Anonymous credit is the word `Anonymous`. Public JSON for that row has no email, no school domain, no title, no affiliation, and no profile id.
- `GET /v1/profiles/:id` returns 404 for an anonymous profile.
- Export (`GET /v1/me/export`, and **Export my data** on the phone) is the owner's copy and does include their email. It is not a public URL.
- Delete (`DELETE /v1/me`, and **Delete my profile** on the phone) removes the account, sessions, codes, profile, avatar, and unpublished rows. Public rows stay available because the person already granted an open license, but they are rewritten to `Anonymous`, unlinked from the account, stripped of coordinates, and given the anonymous trust weight. CC licenses are not revoked for copies already published; the name is removed.
- No analytics, ads, or third-party trackers are added.
- The safety line **Never eat anything based only on this app.** stays on the profile screen. A profile is not a reason to eat a plant.

## Moderation

- Every public contribution can be reported: `POST /v1/contributions/:id/report` with a short reason. The phone has a **Report** control on each local submission, which hides that row on the phone.
- Three open reports mark a public row `hidden` until an admin acts.
- Photos from a new account are `pending` and are omitted from the public list. "New" means the account is under 7 days old, or it has no photo an admin has approved yet. Reviews and identification notes are not held back; they use a lower trust weight instead (below).
- `GET /v1/admin/queue` and `POST /v1/admin/contributions/:id` with `{ "action": "approve" }` or `{ "action": "hide" }` require `Authorization: Bearer <ADMIN_TOKEN>`. The token is a Worker secret. If it is unset, those routes return 503. The admin payload does not include the account email, including for anonymous submissions.
- The worker is not deployed here, so there is no live queue until the owner deploys and sets the secret.

## Trust weighting

Scores that combine several people's identification notes use a weight, not a flat vote. The same table is in `server/test/fixtures/trust-weights.json` and is checked by the phone tests and the worker tests.

Checked in this order. The first matching row wins.

| Who | Weight |
|---|---|
| Photo not yet approved, public or anonymous | 0 (left out of the score) |
| Anonymous review, identification note, or approved photo | 1 |
| Public, verified academic | 2 |
| Public, not verified, account 7 days or older | 1.25 |
| Public, not verified, account under 7 days | 0.75 |

Verified weight is not applied to anonymous rows, so a single anonymous note cannot reveal that the author has a school mailbox. A verified public profile can show the badge and the domain. The weight is a hint for ranking feedback. It is not a statement that the plant is edible, and it does not bypass confidence tiers or the safety rules in `SAFETY.md`.

## Offline behavior

- Create and edit the profile, pick a photo, choose anonymous, choose a license, and read the school-domain hint with no network.
- Saving a review when a profile exists also keeps a credited copy in the local queue. The review remains on the phone either way. **Share with OpenWild** still uses today's GitHub inbox when that inbox is enabled, and it also keeps a credited queue row.
- Sending photos (GitHub form or the photo inbox) and sending an identification correction also append a credited queue row. The existing send path is unchanged. The default photo license string sent to the inbox stays `CC BY 4.0` unless the person picks another license.
- The queue syncs only when `PROFILES_ONLINE` is true and a URL is set. Otherwise `ProfileSync` does not open a socket.
- With no profile, identification, browse, safety, packs, and the current contribute buttons behave as before. The new About entry is an optional profile screen.

## Android build flag

In `android/app/build.gradle.kts`:

- `profilesOnline` defaults to `false` → `BuildConfig.PROFILES_ONLINE`
- `profilesApiUrl` defaults to empty → `BuildConfig.PROFILES_API_URL`

A future build the owner approves can pass `-PprofilesOnline=true -PprofilesApiUrl=https://<worker-host>`. The URL is not written in source. CI rejects a literal host inside `ProfileSync.kt` and rejects a default of `true`.

Network code on the phone is still only in `PackManager`, `PhotoInbox`, `PlantNet`, and `ProfileSync`. `ProfileSync` is the profiles host, and only when the flag is on.

## Hosting and cost

Recommended: **Cloudflare Workers + D1 + R2** on the Workers Free plan. Figures below are from Cloudflare's public pricing pages for Workers, D1, and R2, and from Resend's pricing page, as checked in November 2026. Recheck them before paying for anything.

| Piece | Free allowance | Fits a small study group |
|---|---|---|
| Workers | 100,000 requests/day, 10 ms CPU per request | A few hundred people reading and posting stays under this. $0. |
| D1 | 5 million row reads/day, 100,000 writes/day, 5 GB, 10 databases | Profiles and contributions are tiny. $0. |
| R2 | 10 GB-month, 1 million Class A and 10 million Class B operations/month, egress free | A few thousand JPEG photos (well under 10 GB). $0. |
| Email | Not included for mail to arbitrary students on the Workers Free plan | See below. |

**Estimated cost at this size: $0 per month**, plus a mail provider that stays inside its free quota.

Magic-link email is the part the free Worker does not do by itself:

- **Resend** (or a similar transactional provider) free tier: 3,000 emails/month and 100/day. $0 until that cap. The worker calls it only after a secret is set. This PR does not set that secret and does not send mail.
- **Cloudflare Email Sending** to any recipient requires the Workers Paid plan: $5/month, which includes 3,000 outbound emails and then about $0.35 per extra 1,000. Use this if one vendor is worth more than staying on $0.

If the free CPU limit (10 ms) is too tight on a cold start while the domain set is built, the set is cached in the isolate after the first request. Moving to Workers Paid ($5/month) raises the CPU limit if that ever shows up in logs.

When the free tier is actually exceeded, the next step is Workers Paid at $5/month (higher included D1 usage; R2 still bills past its own free tier at $0.015/GB-month and $4.50 per million Class A writes). That is a later decision, not this change.

### Alternatives

| Option | When it fits | Cost shape |
|---|---|---|
| Do nothing online (this PR's default) | Keep the GitHub photo inbox and on-phone profiles | $0. No new service. |
| Workers + D1 + R2 + Resend free | Owner wants sign-in and public credits, traffic is small | $0 while under the quotas above. |
| Workers Paid + Cloudflare Email Sending | Owner wants mail and compute from one vendor | $5/month to start. |
| Supabase (hosted Postgres, storage, auth) | Team would rather not run a Worker | Free tier exists; their auth product is a separate account system. We would still disable social logins. Not the default. |
| One small VPS and SQLite | Full control, more patching | A few dollars a month and someone to mind the machine. |

Region packs stay on GitHub Releases, as they do today. Profiles do not replace that.

## What is implemented in this change

On the phone, behind the existing offline app:

- Profile screen: name, photo, title, affiliation, anonymous switch, license, school-email hint, verified badge only when the flag is actually set, credit line, local contributions, report, export, delete, safety footer.
- About links to the profile. The old privacy card is unchanged while online profiles are off.
- Local queue for reviews (when a profile exists, or when the person taps share), photos, and identification feedback, with the credit and license copied on.
- `ProfileSync` uploads only if the build flag and URL are set. The default build does not.

In `server/`:

- Worker (`src/index.js`, `src/handler.js`), D1 schema, R2 photo storage in the code, academic check, trust weights, moderation, delete, and export.
- Tests in `server/test/` run with Node's built-in test runner and an in-memory SQLite database. No Cloudflare account.

Not done, on purpose:

- No deploy, no `wrangler` login, no buckets created, no email sent, no API token committed.
- The phone does not contact a profiles host in the default build.
- Admin review is an API, not a separate app.
- The verified badge cannot be earned until a mailer exists and the owner turns the flag on.

## Deploy steps (do not run from CI)

Run these by hand, from `server/`, after the owner agrees. `npx wrangler` is enough; do not commit its login.

1. `npx wrangler login`
2. `npx wrangler d1 create openwild-profiles` and paste the database id into `wrangler.toml` (`database_id`).
3. `npx wrangler r2 bucket create openwild-photos`
4. `npx wrangler d1 execute openwild-profiles --remote --file=schema.sql`
5. `npx wrangler secret put ADMIN_TOKEN` and type a long random value. Do not put it in `wrangler.toml`.
6. Choose a mailer. `npx wrangler secret put RESEND_API_KEY` and set `EMAIL_FROM` (a verified sender on that account) in `wrangler.toml` `[vars]` or as a secret. The worker sends through Resend only when both are present. Tests inject `env.MAILER` instead and never call Resend. Until one of those is set, `POST /v1/auth/start` returns 503 and sends nothing. Do not log the one-time code. Cloudflare Email Sending is the other option, and it needs the Workers Paid plan.
7. `npx wrangler deploy`
8. Build the app with `-PprofilesOnline=true -PprofilesApiUrl=https://<the worker host>`. Leave the default in git as `false` until that build is the one you mean to ship.
9. Confirm a public contribution JSON has no email, an anonymous row is credited `Anonymous`, a new account's photo stays off the public list, and delete removes the email.

`server/wrangler.toml` ships with `database_id = "replace-after-create"` and `EMAIL_MODE = "off"`. Deploy will not succeed until step 2 replaces the id. That is intentional.
