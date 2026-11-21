# Send queue worker

Cloudflare Worker for optional OpenWild sends. People do not sign in. The worker is not deployed from this repository.

Design and deploy steps: [docs/PROFILES-DESIGN.md](../docs/PROFILES-DESIGN.md).

```bash
node --test test/profiles.test.js
```

Tests use Node's built-in SQLite. They do not contact Cloudflare and they do not send email. Hosting needs D1, R2, and an `ADMIN_TOKEN` secret.
