-- One row per install (random UUID from the app). Updated by every ping/crash/error.
CREATE TABLE installs (
  uuid TEXT PRIMARY KEY,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  version TEXT,
  version_code INTEGER,
  sdk INTEGER,
  locale TEXT
);
CREATE INDEX installs_last_seen ON installs(last_seen);

-- Crash / error reports (already redacted by the app).
CREATE TABLE events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL,
  uuid TEXT NOT NULL,
  type TEXT NOT NULL,
  version TEXT,
  sdk INTEGER,
  fp TEXT NOT NULL,
  payload TEXT NOT NULL
);
CREATE INDEX events_ts ON events(ts);
CREATE INDEX events_fp ON events(fp, ts);

-- The same failure grouped across phones and versions.
CREATE TABLE fingerprints (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  count INTEGER NOT NULL,
  last_version TEXT
);

-- Small counters for rate limits (per install per hour, Telegram alerts per hour).
CREATE TABLE rate (
  key TEXT NOT NULL,
  bucket INTEGER NOT NULL,
  n INTEGER NOT NULL,
  PRIMARY KEY (key, bucket)
);
