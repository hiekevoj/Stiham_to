# ⚡ Stíhám to? (PMDP Plzeň)

> **Inteligentní asistent pro MHD v Plzni** • Přímý live konektor PMDP, chytré slučování zastávek v uzlech, výpočet stihnutelnosti spojů podle vaší chůze, automatické spouštění po odchodu z domova a zamykací obrazovka Androidu v reálném čase.

[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue.svg)]()
[![PMDP](https://img.shields.io/badge/Data-PMDP%20Plzeň%20Live-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)]()

---

## 🎯 O projektu

Aplikace **Stíhám to?** řeší každodenní problém cestujících v Plzni: *„Když vyrazím ze dveří nebo z kanceláře, stihnu nejbližší tramvaj nebo trolejbus?“*

Běžné aplikace zobrazují tabule jednotlivých sloupků nebo vyžadují zdlouhavé vyhledávání. **Stíhám to?** funguje proaktivně:
1. **Chytrý uzel (Smart Clustering):** V místech jako *Sady Pětatřicátníků*, *CAN*, *U Práce* nebo *Hlavní nádraží* automaticky sloučí všechny zastávky v okruhu do jednoho přehledu a prioritizuje páteřní trakci (tramvaje).
2. **Kalkulace stihnutelnosti:** Vypočítává přesnou pěší vzdálenost k označníku a porovnává ji s reálným zpožděním vozu. Okamžitě víte, zda spoj **stíháte v klidu**, **je potřeba zrychlit do kroku**, **musíte běžet**, nebo už **nemá smysl spěchat**.
3. **Automatické probuzení (Smart Trigger):** Detekuje odchod z domova (odpojení od domácí Wi-Fi + rozpoznání chůze přes Activity Recognition) a automaticky zobrazí živou notifikaci na zamykací obrazovce.
4. **Jetpack Compose & Material 3:** Moderní deklarativní UI s podporou dynamického přebarvování podle tapety (Material You na Androidu 12+) i plnohodnotným Dark Mode.
5. **Přímé aktualizace z GitHubu (In-App Updater):** Aplikace umí v Nastavení přímo zkontrolovat novou verzi na GitHubu, stáhnout APK s podporou redirectů a spustit aktualizaci bez nutnosti Google Play Store.
6. **Android Live Notifikace na zamykací obrazovce:** Připnutá notifikace s interaktivními tlačítky pro filtr trakce a okamžité probuzení/uspání.
7. **Nulová závislost na externích placených mapách:** Kalibrovaný offline pěší model pro Plzeň namísto nestabilních veřejných routerů.

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
│   │   ├── AppUpdateManager.kt      # In-App aktualizace z GitHub Releases & version.json
│   │   └── GeofenceManager.kt       # Geofencing kolem vybraných uzlů
│   ├── app/src/main/res/            # Ikony, vektory linek, motivy
│   └── build-apk.sh                 # Automatizovaný build skript pro sestavení APK
├── data/
│   └── stops.json                   # Kompletní offline databáze všech 328+ zastávek PMDP
├── StihamTo-Plzen.apk               # Zkompilovaný balíček APK připravený k instalaci
└── version.json                     # Metadata verzí pro GitHub In-App Updater
```

---

## 🚀 Jak začít

### 1. Stažení a instalace Android APK

Předpřipravený instalační balíček najdete přímo v kořenu repozitáře:
- **[StihamTo-Plzen.apk](StihamTo-Plzen.apk)**

**Instalace do telefonu přes ADB:**
```bash
adb install -r StihamTo-Plzen.apk
```
*Nebo soubor jednoduše stáhněte do telefonu a povolte instalaci z neznámých zdrojů.*

---

### 2. Kompilace Android aplikace ze zdrojových kódů

Projekt obsahuje skript pro sestavení pomocí Gradle:

```bash
# Sestavení debug APK
./android/build-apk.sh
```

Výsledné APK se automaticky uloží do `StihamTo-Plzen.apk`.

---

## 🛠️ Použité technologie

- **Android:** Kotlin, Android Jetpack Compose, Material 3, Coroutines & Flow, Google Play Services (Location & Activity Recognition).
- **Data:** PMDP (Plzeňské městské dopravní podniky) live feed & GTFS zastávky.

---

## 📄 Licence

Tento projekt je open-source pod licencí MIT.
