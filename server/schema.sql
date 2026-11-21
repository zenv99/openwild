-- Optional sends. No accounts and no email. See docs/PROFILES-DESIGN.md.

CREATE TABLE IF NOT EXISTS submission (
  id TEXT PRIMARY KEY,
  install_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  species TEXT NOT NULL,
  region_id TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  rating INTEGER,
  license TEXT NOT NULL,
  credit TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  affiliation TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  photo_key TEXT,
  avatar_key TEXT,
  avatar_status TEXT,
  share_location INTEGER NOT NULL DEFAULT 0,
  coarse_lat REAL,
  coarse_lon REAL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS report (
  id TEXT PRIMARY KEY,
  submission_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS submission_install ON submission (install_id, created_at);
CREATE INDEX IF NOT EXISTS submission_public ON submission (status, species, region_id);
