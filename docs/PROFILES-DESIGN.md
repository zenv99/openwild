# Optional credit on a send

People never sign in. Identification, safety text, and region packs work with no name at all. A name is asked only on the form that sends a review, a photo, an identification note, or other data. Leaving it blank credits the item to Anonymous.

Verified academic badges are not part of this version. They could be added later as an opt-in, still without requiring a sign-in.

## On the send form

| Field | Rules |
|---|---|
| Your name (optional) | Up to 60 characters. Blank means Anonymous. |
| Title and affiliation | Collapsed until the person opens them. Kept only when the name is not blank. |
| Remember my name | Unchecked by default. When checked, the phone saves the name, title, affiliation, and picture and prefills the next send. Unchecking wipes that saved copy. |
| Picture | Optional. Camera or gallery. Cropped to a square and scaled to 256 px. Re-encoded as JPEG, which drops EXIF, including GPS. Sent only with a named credit, never with Anonymous. |
| Photo license | On the photo form only. Default CC BY 4.0. Also CC0 or CC BY-SA 4.0. |

Settings has **Clear my saved name**, which wipes the remembered name, title, affiliation, and picture. It does not delete sends already queued.

The phone also keeps a random install id (a UUID created on the device). It is not derived from the person. The server uses it only to rate-limit and to let that phone delete its own sends.

## What is stored with a send

Each queued item copies the credit at send time: name or Anonymous, title, affiliation, license, and the picture bytes only when the credit is a name. Location stays empty unless that send opts in, then one 0.1 degree cell (about 11 km), the same rounding the wrong-ID sheet already uses. Anonymous sends drop the picture and the coordinates.

`profilesOnline` defaults to false and the API URL defaults to empty. `ProfileSync` returns before it opens a connection. The app works fully offline.

## Server

`server/` is a Cloudflare Worker. It is not deployed from this repository. There is no account table, no password, and no email.

`POST /v1/submissions` accepts the item plus `X-Install-Id`. Photos are `pending` and stay out of the public list until an admin approves them. Reviews and identification notes are public immediately. A picture starts `pending` and is omitted from the public credit until approved. Three reports hide the item. `DELETE /v1/submissions/:id` with the same install id removes that send. A different install id is rejected. Every note has trust weight 1.

Admin routes need `Authorization: Bearer <ADMIN_TOKEN>`. If the secret is unset they return 503. The queue payload does not include the install id.

## Hosting

Workers + D1 + R2 on the free tier. No email provider.

| Piece | Free allowance | This use |
|---|---|---|
| Workers | 100,000 requests/day | Small send volume. $0. |
| D1 | 5 million reads/day, 100,000 writes/day, 5 GB | Submission rows. $0. |
| R2 | 10 GB, free egress | Plant photos and small credit pictures. $0. |

The only secret is `ADMIN_TOKEN` (`wrangler secret put ADMIN_TOKEN`). Deploy by hand from `server/` after replacing `database_id` in `wrangler.toml`: create the D1 database, create the R2 bucket, apply `schema.sql`, set the secret, then `wrangler deploy`. Leave `profilesOnline` false in git until that build is the one you mean to ship.

Prices were checked against Cloudflare's public pages in November 2026. Recheck them before paying for anything. Past the free tier, Workers Paid starts at $5/month.
