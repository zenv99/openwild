# Profile worker

Cloudflare Worker for optional OpenWild profiles. It is not deployed from this repository.

The design, privacy rules, and deploy steps are in [docs/PROFILES-DESIGN.md](../docs/PROFILES-DESIGN.md).

```bash
node --test test/profiles.test.js
```

Tests use Node's built-in SQLite. They do not contact Cloudflare and they do not send email.
