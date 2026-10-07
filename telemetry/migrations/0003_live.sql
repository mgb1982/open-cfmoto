-- "Share my ride live" links (src/live.js). Deleted a day after they end.
CREATE TABLE live (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  name TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  ended_at INTEGER,
  lat REAL,
  lon REAL,
  spd INTEGER,
  last_ts INTEGER,
  last_update INTEGER
);
CREATE TABLE live_points (
  id TEXT NOT NULL,
  ts INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL
);
CREATE INDEX live_points_id ON live_points(id, ts);
