-- OpenWild optional profiles. Applied to D1 by hand; see docs/PROFILES-DESIGN.md.
-- Login is a one-time code. This table stores the mailbox only.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS account (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  email_domain TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  verified_academic INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS session (
  token_hash TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS otp (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  salt TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  used INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS profile (
  account_id TEXT PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE,
  display_name TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL DEFAULT '',
  affiliation TEXT NOT NULL DEFAULT '',
  visibility TEXT NOT NULL DEFAULT 'public',
  photo_key TEXT,
  default_license TEXT NOT NULL DEFAULT 'cc-by',
  verified_domain TEXT
);

CREATE TABLE IF NOT EXISTS contribution (
  id TEXT PRIMARY KEY,
  account_id TEXT REFERENCES account(id) ON DELETE SET NULL,
  kind TEXT NOT NULL,
  species TEXT NOT NULL,
  region_id TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  rating INTEGER,
  visibility TEXT NOT NULL,
  license TEXT NOT NULL,
  credit TEXT NOT NULL,
  status TEXT NOT NULL,
  trust_weight REAL NOT NULL,
  photo_key TEXT,
  share_location INTEGER NOT NULL DEFAULT 0,
  coarse_lat REAL,
  coarse_lon REAL,
  created_at INTEGER NOT NULL,
  client_id TEXT
);

CREATE TABLE IF NOT EXISTS report (
  id TEXT PRIMARY KEY,
  contribution_id TEXT NOT NULL REFERENCES contribution(id) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS otp_email_created ON otp (email, created_at);
CREATE INDEX IF NOT EXISTS contribution_public ON contribution (status, species, region_id);
