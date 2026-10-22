# Stíhám to?

> Inteligentní asistent pro městskou hromadnou dopravu. Proaktivní vyhodnocení odjezdů v reálném čase, dynamické shlukování zastávek v uzlech, výpočet stihnutelnosti spojů podle tempa chůze, automatická aktivace při odchodu a průběžný přehled přímo na zamykací obrazovce.

[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue.svg)]()
[![Version](https://img.shields.io/badge/Version-0.1.0-blue.svg)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)]()

---

## Hlavní funkce

Aplikace **Stíhám to?** proaktivně řeší každodenní otázku cestujících: *„Když vyrazím ze dveří nebo z kanceláře, stihnu nejbližší spoj?“*

Běžné jízdní řády vyžadují zdlouhavé vyhledávání a přepínání jednotlivých sloupků. **Stíhám to?** přistupuje k informacím kontextově:

1. **Chytrý uzel (Smart Clustering):** V rozsáhlých přestupních uzlech automaticky sloučí všechny okolní zastávky v nastaveném dosahu (300, 400 nebo 500 m) do jednotného přehledu se zvýrazněním páteřní dopravy.
2. **Kalkulace stihnutelnosti:** Vypočítává přesnou pěší docházkovou vzdálenost k označníku a porovnává ji s reálným zpožděním vozu. Okamžitě víte, zda spoj **stíháte s rezervou**, **je potřeba zrychlit krok**, **musíte běžet**, nebo už spoj odjel.
3. **Automatická aktivace a správa energie:** Rozpoznává odchod z klidové Wi-Fi sítě a souvislou chůzi pomocí senzorů pohybu (Google Activity Recognition). Při pobytu na zastávce udržuje spojení a spolehlivě rozlišuje čekání na spoj od pobytu v budově.
4. **Nativní Android notifikace:** Připnutá notifikace na zamykací obrazovce s interaktivními filtry trakcí a okamžitým ovládáním.
5. **Moderní rozhraní Material 3:** Plné Edge-to-Edge zobrazení bez rušivých pruhů, transparentní systémové lišty, podpora systémového Dark Mode i dynamického barvení Material You.
6. **Přirozená gesta a navigace:** Plynulé přepínání mezi obrazovkami Domů a Nastavení pomocí horizontálního tažení (swipe) i spodní navigační lišty.
7. **Interní správa aktualizací:** Schopnost přímo v nastavení ověřit novou verzi, stáhnout instalační balíček a spustit aktualizaci.

---

## Struktura projektu

```text
Stiham_to/
├── android/                         # Nativní aplikace pro Android (Kotlin, Jetpack Compose)
│   ├── app/src/main/java/cz/plzen/stihamto/
│   │   ├── MainActivity.kt          # Hlavní aktivita, navigace, správa stavu
│   │   ├── LiveDeparturesService.kt # Popředová služba, notifikace na pozadí, senzory
│   │   ├── PmdpRepository.kt        # Datový konektor, algoritmy stihnutelnosti, offline cache
│   │   ├── SmartTriggerMonitor.kt   # Sledování síťových stavů a pohybových aktivit
│   │   ├── ActivityTransitionManager.kt
│   │   ├── AppUpdateManager.kt      # Modul pro ověřování a instalaci aktualizací
│   │   └── ui/                      # Jetpack Compose komponenty a témata
│   ├── app/src/main/res/            # Vektorové ikony, systémové resources, styly
│   └── build-apk.sh                 # Sestavovací skript
├── data/
│   └── stops.json                   # Offline databáze zastávek
├── StihamTo-Plzen.apk               # Instalační balíček APK
└── version.json                     # Metadata verzí pro interní aktualizace
```

---

## Instalace a spuštění

### 1. Stažení předpřipraveného balíčku

Instalační soubor je dostupný přímo v repozitáři:
- **[StihamTo-Plzen.apk](StihamTo-Plzen.apk)**

**Instalace přes ADB:**
```bash
adb install -r StihamTo-Plzen.apk
```
Nebo soubor stáhněte do mobilního zařízení a povolte instalaci aplikací z neznámých zdrojů.

---

### 2. Sestavení ze zdrojových kódů

Projekt využívá Gradle s Kotlin DSL. Pro sestavení je připraven automatizovaný skript:

```bash
./android/build-apk.sh
```

Sestavený balíček APK se automaticky vyexportuje do kořenového adresáře jako `StihamTo-Plzen.apk`.

---

## Použité technologie

- **Platforma:** Android (minSdk 26, targetSdk 34)
- **Jazyk a framework:** Kotlin 1.9.24, Jetpack Compose (BOM 2024.06.00), Material 3
- **Asynchronní operace:** Kotlin Coroutines & Flow
- **Systémové služby:** Google Play Services (Fused Location Provider, Activity Recognition), Android PowerManager & Foreground Service

---

## Licence

Projekt je publikován pod licencí MIT.
