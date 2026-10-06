# RideScreen AA — telemetry receiver

Cloudflare Worker + D1 that receives the app's anonymous pings and crash/error reports
(`AnonymousTelemetry.kt` → `POST /v1/ping`) and notifies the maintainer on Telegram.

- Stores only what the app sends: random UUID, version, Android API level, UI language,
  redacted crash/error text. No IP, country or headers. 180-day retention (weekly cron).
- Same failure on different phones/builds is grouped by a fingerprint (exception + first app
  frames, numbers stripped). Telegram alert on the 1st occurrence, then at 5, 25, 100, 500.
- Rate limits: 30 events/hour per install, 20 Telegram alerts/hour.

Telegram commands (only answered in `TELEGRAM_CHAT_ID`): `/resumen [días]`, `/fallos [días]`,
`/ver_<id>`, `/versiones`. A weekly summary arrives on Mondays.

## Deploy

Automatic from `.github/workflows/telemetry.yml` on every push touching `telemetry/`
(or run it by hand). Repo secrets: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`. The workflow creates the D1 database, applies
`migrations/`, deploys, stores the Telegram secrets and sets the bot webhook.

Then set the printed `https://ridescreen-telemetry.<subdomain>.workers.dev` as the
`TELEMETRY_URL` **repo variable** (Settings → Secrets and variables → Actions → Variables) so
app builds report there. Without it, builds send nothing.
