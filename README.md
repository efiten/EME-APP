# EME APP (ON5GS)

Android-app voor EME (moonbounce), gemaakt voor ON5GS.
*Android app for EME (moonbounce) operators.*

## Tabbladen

| Tabblad | Wat |
|---|---|
| **Maan** | AZ/EL van de maan voor je locator (Meeus, topocentrisch), datum en tijd (lokaal + UTC), opkomst / culminatie / ondergang, wereldkaart met het gebied waar de maan boven de horizon staat, de maanfase en de Live CQ-stations in de kleur van hun band. |
| **Deg** | EME-degradatie zoals MMMonVHF (50 – 1296 MHz): nu, en een grafiek van 30 dagen rond een gekozen dag met maanafstand en declinatie. |
| **HB9Q Chat** | De HB9Q EME-chat, **alleen lezen** (afgesproken met HB9Q): berichten en wie recent schreef. Met "Log out". |
| **Chat CQ** | De CQ-aanroepen uit de HB9Q-chat van het laatste half uur, als tabel. |
| **Live CQ** | CQ-spots van W3SZ Live CQ (MAP65/Q65), filter per band, met maanstand per station. |

Verder: instellingen voor callsign, locator en HB9Q-login (versleuteld bewaard met de Android Keystore),
donkerblauw thema en 13 talen (NL, FR, EN, DE, ES, IT, PT, SV, DA, FI, RU, ZH, JA).

## Bouwen

Vereist: Android Studio (of JDK 17+ en de Android SDK, platform 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew testDebugUnitTest -Pscreenshots   # + schermafdrukken in app/build/screenshots/
```

## Projectindeling

```
app/src/main/java/be/on5gs/eme/
  moon/          maanberekening (Meeus), opkomst/ondergang, maanfase, wereldkaart, labelplaatsing
  degradation/   degradatie (MMMonVHF-methode) en het Deg-scherm
  location/      Maidenhead-locator <-> breedte/lengte
  cq/            W3SZ Live CQ (CSV-export), bandkleuren
  chat/          HB9Q-chat (alleen lezen), Chat CQ-herkenning
  settings/      instellingen, versleutelde login, taalkeuze
  ui/theme/      thema
app/src/main/res/values*/strings.xml   vertalingen
app/src/main/assets/land110m.txt       landcontouren voor de kaart
```

## Bronnen en dank

- Maanberekening: Jean Meeus, *Astronomical Algorithms* (hfst. 12, 22, 25, 40, 47, 48); getest tegen astropy.
- Degradatie en hemeltemperatuur-tabel (144 MHz): MMMonVHF (www.mmmonvhf.de/eme.php), naar EME Handbook 2010 / WSJT-gegevens van K1JT.
- Live CQ: W3SZ (w3sz.com/livecq.html), via de officiële CSV-export.
- HB9Q EME-chat: logger.hb9q.ch — alleen lezen, in overleg met HB9Q.
- Landcontouren: Natural Earth 1:110m (public domain), via world-atlas (ISC, © Mike Bostock).

De testgegevens in `app/src/test/resources/` zijn echte (geanonimiseerde e-mail) voorbeelden uit de
HB9Q-chat en W3SZ; deze repository is daarom privé.
