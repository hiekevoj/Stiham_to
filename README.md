# ⚡ Stíhám to? (PMDP Plzeň)

> **Inteligentní asistent pro MHD v Plzni** • Přímý live konektor PMDP, chytré slučování zastávek v uzlech, výpočet stihnutelnosti spojů podle vaší chůze, automatické spouštění po odchodu z domova a zamykací obrazovka Androidu v reálném čase.

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Web-brightgreen.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue.svg)]()
[![PMDP](https://img.shields.io/badge/Data-PMDP%20Plzeň%20Live-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)]()

---

## 🎯 O projektu

Aplikace **Stíhám to?** řeší každodenní problém cestujících v Plzni: *„Když vyrazím ze dveří nebo z kanceláře, stihnu nejbližší tramvaj nebo trolejbus?“*

Běžné aplikace zobrazují tabule jednotlivých sloupků nebo vyžadují zdlouhavé vyhledávání. **Stíhám to?** funguje proaktivně:
1. **Chytrý uzel (Smart Clustering):** V místech jako *Sady Pětatřicátníků*, *CAN*, *U Práce* nebo *Hlavní nádraží* automaticky sloučí všechny zastávky v okruhu 250 m do jednoho přehledu a prioritizuje páteřní trakci (tramvaje).
2. **Kalkulace stihnutelnosti:** Vypočítává přesnou pěší vzdálenost k označníku a porovnává ji s reálným zpožděním vozu. Okamžitě víte, zda spoj **stíháte v klidu**, **je potřeba zrychlit do kroku**, **musíte běžet**, nebo už **nemá smysl spěchat**.
3. **Automatické probuzení (Smart Trigger):** Detekuje odchod z domova (odpojení od domácí Wi-Fi + rozpoznání chůze přes Activity Recognition) a automaticky zobrazí živou notifikaci na zamykací obrazovce.
4. **Android Live Notifikace & Widget:** Připnutá notifikace na zamykací obrazovce s interaktivními tlačítky pro filtr trakce a domovský widget s živými odjezdy.
5. **Nulová závislost na externích placených mapách:** Kalibrovaný offline pěší model pro Plzeň namísto nestabilních veřejných routerů.

---

## 📱 Struktura projektu

```text
Stiham_to/
├── android/                         # Nativní aplikace pro Android (Kotlin)
│   ├── app/src/main/java/cz/plzen/stihamto/
│   │   ├── MainActivity.kt          # Hlavní UI, nastavení domácích Wi-Fi, diagnostika
│   │   ├── LiveDeparturesService.kt # Foreground služba s notifikací a pedometrem
│   │   ├── PmdpRepository.kt        # Komunikace s PMDP API, offline výpočty, cache
│   │   ├── SmartTriggerMonitor.kt   # Wi-Fi monitor & Activity Recognition trigger
│   │   ├── ActivityTransitionManager.kt
│   │   ├── DeparturesWidgetProvider.kt # Domovský widget na plochu
│   │   └── GeofenceManager.kt       # Geofencing kolem vybraných uzlů
│   ├── app/src/main/res/            # Ikony, layouty widgetu, vektory linek
│   └── build-apk.sh                 # Automatizovaný build skript pro sestavení APK
├── src/                             # Node.js backend & PMDP connector
│   ├── server.mjs                   # HTTP server s REST API (/api/stops, /api/departures)
│   ├── pmdpClient.mjs               # Scraping & parsování reálných dat z PMDP
│   └── testLive.mjs                 # CLI testovací skript pro ověření PMDP API
├── public/                          # Webový náhled & simulátor
│   ├── index.html                   # Interaktivní webová aplikace a simulátor notifikace
│   ├── app.js                       # Klientská logika, Geofence slider, GPS
│   ├── styles.css                   # Moderní tmavý designový systém
│   └── StihamTo-Plzen.apk           # Zkompilovaný balíček APK připravený k instalaci
└── data/
    └── stops.json                   # Kompletní offline databáze všech 350+ zastávek PMDP
```

---

## 🚀 Jak začít

### 1. Stažení a instalace Android APK

Předpřipravený instalační balíček najdete přímo v repozitáři:
- **[public/StihamTo-Plzen.apk](public/StihamTo-Plzen.apk)**

**Instalace do telefonu přes ADB:**
```bash
adb install -r public/StihamTo-Plzen.apk
```
*Nebo soubor jednoduše stáhněte do telefonu a povolte instalaci z neznámých zdrojů.*

---

### 2. Spuštění Webového náhledu / Lokálního serveru

Ke spuštění webového serveru a konektoru postačí Node.js (v18+):

```bash
# Spuštění serveru (bez externích npm závislostí)
npm start
```
Otevřete v prohlížeči: **http://localhost:3333**

Server nabízí:
- Webový simulátor chování aplikace (Geofence posuvník, výběr modelových uzlů v Plzni).
- Interaktivní náhled Android notifikace v reálném čase.
- REST API pro zastávky a živé odjezdy:
  - `GET /api/stops?lat=49.746&lon=13.363`
  - `GET /api/departures?lat=49.746&lon=13.363&multi=true`

---

### 3. Kompilace Android aplikace ze zdrojových kódů

Projekt obsahuje skript pro sestavení pomocí Gradle:

```bash
# Sestavení debug APK
./android/build-apk.sh
```

Výsledné APK se automaticky uloží do `StihamTo-Plzen.apk` i `public/StihamTo-Plzen.apk`.

---

## 🛠️ Použité technologie

- **Android:** Kotlin, Android Jetpack, Coroutines & Flow, AndroidX WorkManager, Google Play Services (Location & Activity Recognition), RemoteViews (Widget).
- **Backend:** Node.js (ES Modules, nativní Fetch & HTTP stream).
- **Frontend / Simulátor:** Moderní vanilla JavaScript, CSS custom properties, responzivní layout.
- **Data:** PMDP (Plzeňské městské dopravní podniky) live feed & GTFS zastávky.

---

## 📄 Licence

Tento projekt je open-source pod licencí MIT.
