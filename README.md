# EME APP (ON5GS)

An Android app for EME (moonbounce) operators, made for ON5GS.

## Download

**[Download the app (EME-APP.apk, version 0.16)](https://github.com/on5gsdirk-cmd/EME-APP/raw/main/apk/EME-APP.apk)**

Open the link on your Android phone, open the downloaded file and allow installation from this source.
It installs over earlier versions; your settings and HB9Q login are kept.
This is a debug build signed with a development key, meant for testing.

## Tabs

| Tab | What it does |
|---|---|
| **Moon** | Moon AZ/EL for your locator (Meeus, topocentric), date and time (local + UTC), moonrise / culmination / moonset, and a world map showing where the moon is above the horizon, the current moon phase and the Live CQ stations in the colour of their band. |
| **Deg** | EME degradation as calculated by MMMonVHF (50 – 1296 MHz): the value right now, plus a 30-day graph around a chosen date with moon distance and declination. |
| **HB9Q** | The HB9Q EME chat, **read only** (agreed with HB9Q), per band, with three sub-pages: **Messages**; **QRV**, the stations that wrote recently; and **Chat CQ**, the CQ calls of the last 60 minutes as a table. Includes a "Log out" button. |
| **N0UK** | The N0UK WSJT EME chat (144 MHz), **read only**, with the same three sub-pages: Messages, QRV (with date and time) and Chat CQ (last 60 minutes, with frequency, period, mode, locator and moon elevation; a CQ is greyed out when the station later wrote "stop"). Fetched at most once every 90 s. |
| **Live CQ** | CQ spots from W3SZ Live CQ (MAP65/Q65), filtered per band, showing whether each station has the moon above its horizon. |

Also: settings for callsign, locator and HB9Q login (stored encrypted with the Android Keystore),
a dark blue theme, and 13 languages (NL, FR, EN, DE, ES, IT, PT, SV, DA, FI, RU, ZH, JA).

## Building

Requirements: Android Studio (or JDK 17+ and the Android SDK, platform 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew testDebugUnitTest -Pscreenshots   # also renders screenshots into app/build/screenshots/
```

## Project layout

```
app/src/main/java/be/on5gs/eme/
  moon/          moon position (Meeus), rise/set, moon phase, world map, label placement
  degradation/   degradation (MMMonVHF method) and the Deg screen
  location/      Maidenhead locator <-> latitude/longitude
  cq/            W3SZ Live CQ (CSV export), band colours
  chat/          HB9Q chat (read only), Chat CQ parsing
  n0uk/          N0UK chat (read only), its CQ parsing
  settings/      settings, encrypted login, language selection
  ui/theme/      theme
app/src/main/res/values*/strings.xml   translations
app/src/main/assets/land110m.txt       coastlines for the world map
```

## Sources and credits

- Moon calculations: Jean Meeus, *Astronomical Algorithms* (chapters 12, 22, 25, 40, 47, 48); tested against astropy.
- Degradation and the 144 MHz sky temperature table: MMMonVHF (www.mmmonvhf.de/eme.php), based on the EME Handbook 2010 / WSJT data by K1JT.
- Live CQ: W3SZ (w3sz.com/livecq.html), via its official CSV export.
- HB9Q EME chat: logger.hb9q.ch — read only, in agreement with HB9Q.
- N0UK WSJT EME chat: www.chris.org/cgi-bin/jt65emeA — read only.
- Coastlines: Natural Earth 1:110m (public domain), via world-atlas (ISC, © Mike Bostock).

The test data in `app/src/test/resources/` are real samples from the HB9Q chat, the N0UK chat
and W3SZ Live CQ, used to test the parsers. All e-mail addresses in them have been replaced by `xxxx@test.com`.
