-- Which channel the install came from: "github" (GitHub Releases) or "play" (Google Play).
ALTER TABLE installs ADD COLUMN store TEXT NOT NULL DEFAULT 'github';
