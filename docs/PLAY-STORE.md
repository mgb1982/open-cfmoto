# RideScreen AA on Google Play — status and checklist

Two builds come from the same code (Gradle product flavors, dimension `store`):

| | `github` | `play` |
|---|---|---|
| applicationId | `dev.zanderp.opencfmoto` (unchanged, installs over v1/v1.1) | `io.github.mgb1982.ridescreen` |
| Updates | in-app check against GitHub Releases | Google Play only (self-update is against Play policy) |
| Donations | Ko-fi link (+ original author's Ko-fi) | Google Play Billing consumables, no external links |
| ABIs | arm64-v8a APK | all (AAB, Play splits it) |
| Wear OS app | same id as the phone app | same id as the phone app |

The two can't replace each other: Play re-signs with its own key. Moving from GitHub to Play
means installing the Play app separately.

## Done in code
- [x] Flavors in `:app` and `:wear`; CI builds the GitHub APKs + Play AABs (artifact).
- [x] `Support` (src/github, src/play): Ko-fi dialog vs. Play Billing 8 consumables
      `donate_cafe`, `donate_bocadillo`, `donate_deposito` (consumed right away, unlock nothing).
- [x] Anonymous reports carry `store` so the Telegram summary splits GitHub / Play.

## Pending
- [ ] Export / import of bikes + trips, to move from the GitHub app to the Play app.
- [ ] Play Console account (25 USD, identity verification) + payments profile for in-app products.
- [ ] Upload key (real keystore, CI secret) — Play rejects debug-signed bundles.
- [ ] Create the three in-app products (suggested 2 €, 5 €, 10 €).
- [ ] Store listing: name "RideScreen", short/long description, icon 512 px, feature graphic,
      screenshots (phone + Wear OS), privacy policy URL (PRIVACY.md).
- [ ] Data safety form (anonymous ID + crash logs, optional, not shared; location only on device).
- [ ] Declarations: foreground service types (mediaProjection, connectedDevice, location,
      microphone), SYSTEM_ALERT_WINDOW use, location permission video if requested.
- [ ] Closed test with 12+ testers for 14 days (personal accounts), then production.
