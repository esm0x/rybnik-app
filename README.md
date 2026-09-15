# Rybnik — aplikacja miejska

Natywna aplikacja Android dla mieszkańców Rybnika: wydarzenia, harmonogram odpadów,
rozkład jazdy, lokalne wiadomości i jakość powietrza.

## Stan obecny (v0.3)

Wszystkie moduły działają na realnych danych.

| Moduł | Źródło | Skala |
|---|---|---|
| **Start** | agregat pozostałych modułów | dashboard „co dziś ważnego" |
| **Wydarzenia** | TZR, iRybnik, biletyna.pl, 3 domy kultury, ROW | ~126 wydarzeń |
| **Transport** | GTFS z KM Rybnik | 626 przystanków, 44 linie, 71 tys. odjazdów |
| **Śmieci** | 16 PDF-ów z rybnik.eu (EKO Sp. z o.o.) | 98 rejonów, 880 ulic, 27 dzielnic |
| **Wiadomości** | rybnik.com.pl, rybnik.eu, nowiny.pl, tuRybnik | 120 pozycji, alerty na górze |
| **Powietrze** | GIOŚ, stacja Rybnik-Borki (834) | PM10, PM2,5 + indeks jakości |

Powiadomienia: wywóz odpadów (wieczór przed), ulubione wydarzenie (dzień przed),
alert smogowy (próg do ustawienia) i komunikaty miejskie.

## Stack

- Kotlin 2.0.21 + Jetpack Compose (Material 3), Navigation Compose
- ViewModel + StateFlow, OkHttp 4.12, kotlinx.serialization 1.7.3
- **Room** — rozkład jazdy (71 tys. odjazdów to za dużo na plik JSON)
- **DataStore** — adres, ulubione, ustawienia powiadomień
- **WorkManager** — przypomnienia
- Scrapery: Python 3.12, `requests` + `beautifulsoup4` + `pdfplumber`
- `minSdk 26`, `targetSdk 35`, AGP 8.7.0, Gradle 8.9

## Układ projektu

```
app/src/main/java/eu/rybnik/events/
  RybnikApp.kt                 nawigacja: 5 zakładek + ekrany szczegółowe
  RybnikApplication.kt         object Graph — ręczne DI
  core/
    net/Remote.kt              RemoteConfig (GH_USER!), CachedRemoteSource
    prefs/UserPrefs.kt         DataStore: adres, ulubione, powiadomienia
  data/
    Event.kt, RemoteEventRepository.kt
    waste/                     model + dopasowanie adresu + reguły tygodniowe
    transit/                   Room, import GTFS, wyszukiwanie odjazdów
    news/, air/
  ui/
    home/  events/  waste/  transit/  news/  more/  common/  theme/
  work/Reminders.kt            WorkManager + kanały powiadomień

scraper/
  CONTRACT.md                  wiążący kontrakt JSON <-> Kotlin
  scraper.py                   wydarzenia (wiele źródeł)
  waste.py                     parser PDF-ów z harmonogramami
  news.py                      RSS + scraping rybnik.eu
  transit.py                   rozwiązuje adres aktualnego GTFS
  data/*.json                  generowane, commitowane przez CI
```

## Uruchamianie

1. Otwórz **ten** folder (ten z `settings.gradle.kts`) w Android Studio.
2. W `app/src/main/java/eu/rybnik/events/core/net/Remote.kt` ustaw `GH_USER`
   na swoją nazwę użytkownika GitHub.
3. Gradle sync → Run na urządzeniu z API 26+.

Z terminala: `./gradlew assembleDebug`

## Jak płyną dane

Scrapery działają w GitHub Actions i commitują JSON-y do `scraper/data/`.
Aplikacja pobiera je z `raw.githubusercontent.com` i cachuje lokalnie, więc po
pierwszym uruchomieniu działa offline. Rozkład jazdy jest wyjątkiem: `transit_meta.json`
zawiera tylko adres aktualnego `gtfs.zip`, a apka pobiera i importuje go sama do Room.

Kontrakt pól opisuje `scraper/CONTRACT.md` — **nazwy pól i wartości enumów są wiążące**,
Kotlin deserializuje je 1:1.

## Pułapki w danych (zweryfikowane, obsłużone w kodzie)

- **GTFS**: `calendar.txt` ma wszystkie flagi dni = 0 — kursowanie wynika wyłącznie
  z `calendar_dates.txt`. Naiwny parser zwróci zero odjazdów. `shapes.txt` jest pusty
  (brak geometrii tras). Jedna linia ma `route_short_name` = `-->` mimo 87 realnych kursów.
  ID załącznika z GTFS zmienia się co edycję, więc `transit.py` rozwiązuje je ze strony.
- **Śmieci**: harmonogram zależy od **ulicy, numeru i parzystości**, nie od dzielnicy.
  Zabudowa jedno- i wielorodzinna to dwa różne modele — pierwsza ma konkretne daty,
  druga reguły typu „piątek tydzień nieparzysty". W PDF-ach puste miesiące renderują się
  jako `-`, więc tokeny trzeba wiązać z kolumnami po współrzędnej X, nie dzielić stringa.
- **GIOŚ**: wysłanie nagłówka `Accept: application/json` powoduje HTTP 406.
  Klucze JSON są polskimi zdaniami. Progi PM10: informowanie 100, alarm 150 µg/m³
  (starsze źródła podają nieaktualne 200/300). Jeden wskaźnik potrafi mieć **kilka
  stanowisk** — Rybnik-Borki ma dwa PM10 (5466 automatyczne, 5467 manualne).
  Stanowiska manualne zwracają HTTP 400 na dane bieżące, bo wyniki są publikowane
  dopiero po 4–8 tygodniach, a odpowiedź API nie mówi, które jest które. Dlatego
  aplikacja próbuje kolejnych id, aż któreś odda pomiar.
- **rybnik.eu**: nie ma RSS-a, tylko HTML. Sekcja „komunikaty" to worek na ogłoszenia,
  nie tablica awarii — o priorytecie ALERT decydują słowa kluczowe.

## Znane ograniczenia

- Rozkład jazdy nie ma geometrii tras (brak `shapes.txt`), więc „trasa linii" to
  lista przystanków najdłuższego kursu, nie linia na mapie. Mapy nie ma w ogóle.
- Brak odjazdów na żywo. KM Rybnik nie publikuje GTFS-Realtime; istnieje
  nieudokumentowane API z odliczaniem, ale jest bez kontraktu i może zniknąć.
- Trzy wpisy adresowe z PDF-ów gubią wyjątki („poza numerem 205D") — zakres
  wychodzi minimalnie za szeroki. Logowane jako `[warn]` przy generowaniu.
- `Impreza` ma mało wydarzeń poza sezonem klubowym — klasyfikator działa,
  ale we wrześniu w źródłach po prostu nie ma imprez.
- DK Chwałowice i DK Niedobczyce nie mają eksportu iCal, więc ich parsery są kruche.
- Brak benzo(a)pirenu, mimo że to obok PM10 główny problem Rybnika. Mierzy się go
  wyłącznie manualnie (analiza laboratoryjna filtrów), więc wartości „na teraz"
  nie istnieją — GIOŚ udostępnia je po tygodniach przez API danych archiwalnych.

## Roadmapa

1. ✅ v0.1 — szkielet, mock dane
2. ✅ v0.2 — realne wydarzenia z TZR
3. ✅ v0.3 — wszystkie moduły na realnych danych + powiadomienia + smog
4. ⏭️ Mapa przystanków i tras (OSM, bo GTFS nie ma geometrii)
5. ⏭️ Odjazdy na żywo z API Habara, za flagą — może zniknąć bez ostrzeżenia
6. ⏭️ Ciemny motyw — `DarkScheme` jest w `Theme.kt`, wymaga przejścia testowego
7. ⏭️ Widget na pulpit: najbliższy wywóz + smog
8. ⏭️ Zgłaszanie usterek do miasta (wymaga backendu)

## Notatki projektowe

- Paleta celowo nie jest domyślnym Material 3: głęboki petrol z ciepłym amber.
  Dynamic color jest wyłączony, żeby apka wyglądała tak samo na każdym telefonie.
- Kolory kategorii i typów odpadów siedzą na enumach, więc dodanie wartości to
  zmiana w jednej linii, a kolor propaguje się na filtry, kafelki i kropki w kalendarzu.
- Pakiet nazywa się `eu.rybnik.events` z czasów, gdy apka robiła tylko wydarzenia.
  Zmiana wymaga przepisania `applicationId` i przeinstalowania — świadomie odłożone.
