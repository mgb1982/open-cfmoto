# Supported bikes

OpenCfMoto talks to dashes that show a **MotoPlay / EasyConnect pairing QR** — the Carbit software
framework from Wuhan CARBIT Information Co., Ltd. (also seen as Yi Lian / EasyConn). CFMoto is the
best-tested brand; several other manufacturers license the same stack.

**You do not need a T‑BOX.** T‑BOX is for the official CFMOTO RIDE cloud / subscription features.
OpenCfMoto only needs the dash Wi‑Fi (or Wi‑Fi Direct) QR — **US and international** markets both work.

**Quick test:** on the bike, open the phone-connection / MotoPlay / EasyConnect screen. If you see a
QR code, OpenCfMoto can try to connect. No QR → this app cannot join that dash.

Stock Carbit Ride / brand companion apps (MotoFun, etc.) are separate. OpenCfMoto uses the same QR
path to project **wireless Android Auto** (not Apple CarPlay).

Community reports welcome in [Discord](https://discord.gg/xRt5yZy2U) so we can keep this list current.

### Brands (supported & welcome)

| | |
| --- | --- |
| **Confirmed** | **CFMoto** · **Voge** · **Moto Morini** · **Morbidelli** · **GOES** / **Gladiator** (CFORCE rebadges) |
| **Same Carbit / EasyConnect path — try Connect** | **Zontes** · **Benelli** · **QJ Motor** · **Longjia** · other TFT dashes with a pairing QR |

**📸 Dash showcase:** **[SHOWCASE.md](SHOWCASE.md)** — curated Android Auto photos from the community
(one hero shot per confirmed model). Add yours in Discord `#confirmed-working`.

---

## Confirmed with OpenCfMoto

Riders have projected Android Auto with these (US + international as noted):

### CFMoto

| Model | Notes |
| --- | --- |
| **800MT** (Explore / Explore GT) | Landscape touch (CFDL26) |
| **800MT‑X** / **1000MT‑X** | Portrait CFDL26; handlebar-primary by default |
| **800NK** (US CRCP / sdk 0.9.23.x) | Non‑touch; dual PXC heartbeat |
| **800NK Advanced** | Near-square touch (~720×712); use Screen margins for the MotoPlay pull-down |
| **450SR** (+ SR‑S / TC class) | Non‑touch CFDL16; handlebar + on-screen pad |
| **450CL‑C** / **CL‑C450** | Often Wi‑Fi Direct (P2P) — Setup → Wi‑Fi **Auto** or **P2P** |
| **150SC** (scooter) | Community-confirmed; same QR / EasyConnect path |
| **CFORCE 850 / 1000** (ATV TFT) | Community-confirmed |
| **GOES Terrox 1000** / **Gladiator G3 1000** | Community-confirmed — CFORCE 1000 rebadges; same EasyConnect QR path; **1280×720** often looks clean |

### Other brands (community-confirmed)

| Model | Notes |
| --- | --- |
| **Voge DS800 Rally** | Community-confirmed — Carbit / EasyConnect QR |
| **Moto Morini X-Cape 649** | Community-confirmed (also styled Xcape 649) |
| **Moto Morini X-Cape 700** | Community-confirmed |
| **Moto Morini Seiemmezzo** | Community-confirmed — MotoFun / EasyConnect QR |
| **Morbidelli T1002VX** | Community-confirmed (Argentina) — Carbit / EasyConnect QR |
| **QJ Motor 600SX / 550SX** (2026) | In progress — QR works (`qj-5G-*`, modelId 37501); use Setup → Wi‑Fi **AP** if Auto mis-picks P2P |

---

## Other brands (Carbit / EasyConnect)

These brands commonly ship TFT dashes that license the same Carbit EasyConnect-style pairing
(QR → bike Wi‑Fi → projection). **If your unit shows a pairing QR, try Connect.** Unknown model IDs
fall back to the Legacy profile. Please report success or failure (with a log) in Discord so we can
promote models to “confirmed.”

| Brand | Notes / examples |
| --- | --- |
| **Voge** | **DS800 Rally confirmed**; other EasyConnect TFT models welcome |
| **Zontes** | Same Carbit framework on many TFT dashes |
| **Moto Morini** | **X-Cape 649 / 700** and **Seiemmezzo** confirmed; often **MotoFun** companion branding. Pairing QR may be `admin.motomorini.com/…?Wifi=SSID#password#mac&MachineID=…&ProductID=…` (not Carbit `ssid=`/`pwd=`) — supported in OpenCfMoto. Do **not** scan the vehicle info QR (`code:…color:…`). |
| **Benelli** | e.g. TRK 702 / 702X class — SoftAP SSID/password or QR when shown |
| **QJ Motor** | **600SX / 550SX (2026) testing**; Fort 4.0 and other EasyConnect TFTs |
| **Morbidelli** (formerly MBP) | **T1002VX confirmed**; other T1002V-class with Carbit dash welcome |
| **Longjia** | e.g. **V-Bob 650** — Europe often uses **MotoFUN** / **Carbit Ride**. Unconfirmed; try Connect (or Mirror) if the dash shows a pairing QR. Close the official companion app first so it does not hold the link ports. |

---

## Full list — known CFMoto MotoPlay / EasyConnect dashes

If your model appears below **or** shows a pairing QR, try OpenCfMoto. Trim / year / region variants
(e.g. “Sport”, “TC”, “Explore GT”) usually share the same dash protocol when the QR is present.

### Naked (NK)

- 125NK
- 450NK
- 675NK
- 800NK
- 800NK Advanced
- 800NK Sport
- 800NK (US CRCP)

### Sport (SR)

- 300SR
- 450SR
- 450SR‑S
- 450SR TC
- 500SR VOOM
- 675SR
- 675SR‑R

### Touring / Adventure (MT)

- 450MT
- 700MT
- 700MT Adventure
- 800MT‑X
- 800MT Explore
- 800MT Explore GT
- 1000 MT‑X

### Cruiser (CL)

- 450CL‑C
- CL‑C450

### Scooter

- 150SC

### ATV / SSV (TFT dash)

- CFORCE 800 (TFT, typically 2024+)
- CFORCE 850 Touring
- CFORCE 1000 (TFT, typically 2024+)
- CFORCE 1000 Touring
- GOES Terrox 1000 / Gladiator G3 1000 (CFORCE 1000 rebadges)

### Other / regional

- U10 Pro (where the dash offers MotoPlay / EasyConnect QR)

---

## Usually no EasyConnect QR (won’t work with OpenCfMoto)

These are commonly listed **without** a MotoPlay phone-projection QR. If your unit somehow has the
QR anyway, try it and tell us.

- 800MT Sport
- 800MT Touring
- 450SR World Champion Edition
- 700CL‑X (Adventure / Heritage / Sport)
- PAPIO (and similar mini bikes without the EasyConnect projection screen)

---

## How to use this list in the app

Setup → **Supported bikes** shows the same list. Profiles you can force in Setup:

**Auto** · **Legacy** (CFDL16) · **800NK** · **800MT** · **1000 MT‑X** · **800NK Adv** · **CL‑C450**

Touch dashes → use the screen (and **Dash view**). Non‑touch / focus-mode → **Controls** + Bluetooth
handlebars.
