# Kontrakt danych — scrapery ↔ aplikacja

Każdy scraper zapisuje JSON do `scraper/data/`. Aplikacja pobiera te pliki
z `raw.githubusercontent.com` i cachuje lokalnie. **Nazwy pól i wartości enumów
są wiążące** — Kotlin deserializuje je 1:1, literówka = brak danych w apce.

Wspólna koperta dla każdego pliku:

```json
{ "generated_at": "2026-09-15T22:47:52", "count": 31, "failures": [], "...": "dane" }
```

`generated_at` — ISO 8601 lokalny. `failures` — lista `{"source": "...", "error": "..."}`,
nigdy nie przerywa zapisu: lepiej wydać częściowe dane niż nic.

---

## events.json

```json
{
  "generated_at": "...", "sources": ["TZR", "iRybnik"], "count": 120, "failures": [],
  "events": [
    {
      "id": "tzr-niezly-burdel",
      "title": "Niezły burdel",
      "category": "Spektakl",
      "start": "2026-09-19T18:00",
      "end": null,
      "venue": "Teatr Ziemi Rybnickiej",
      "description": "Komedia pełna chaosu...",
      "sourceName": "TZR",
      "sourceUrl": "https://..."
    }
  ]
}
```

`category` MUSI być jedną z: `Koncert`, `Spektakl`, `Kabaret`, `Film`, `Festiwal`,
`Wystawa`, `Warsztaty`, `Impreza`, `Sport`, `DlaDzieci`, `Inne`.
`start`/`end` — ISO local bez strefy, minutowa precyzja. 

`id` — globalnie unikalne, prefiks źródła (`tzr-`, `iryb-`, `bilnya-`, `dk-`, `row-`,
`manual-`).

### manual_events.json (wejście, nie wyjście)

Jedyny plik w `scraper/data/`, który **edytuje człowiek, a nie scraper**. Trzyma
wydarzenia bez źródła maszynowego — głównie imprezy klubów Noc i Szepty, które
publikują wyłącznie na Facebooku. `scraper.py` czyta go jak każde inne źródło
i dokłada do `events.json`; nigdy do niego nie pisze.

```json
{
  "_jak_dodac": ["instrukcja dla człowieka — ignorowana"],
  "_wzor": { "…": "szablon do skopiowania — ignorowany" },
  "events": [
    {
      "title": "Nazwa imprezy",
      "category": "Impreza",
      "start": "2026-09-26T21:00",
      "end": null,
      "venue": "Klub NOC",
      "description": "Opis albo null.",
      "sourceUrl": "https://..."
    }
  ]
}
```

Czytany jest **wyłącznie** klucz `events`. Wymagane: `title`, `category`, `start`,
`venue`. `id` powstaje automatycznie jako `manual-<slug>-<data>`, a `sourceName`
przyjmuje nazwę miejsca. Wpis z niepoprawną datą albo bez miejsca jest pomijany
z ostrzeżeniem; nieznana kategoria spada do `Inne`. Zły wpis nigdy nie wywala runu.

---

## waste.json

```json
{
  "generated_at": "...", "year": 2026, "count": 96, "failures": [],
  "rejony": [
    {
      "id": "maroko-nowiny-3",
      "name": "Maroko-Nowiny 3",
      "district": "Maroko-Nowiny",
      "houseType": "SINGLE_FAMILY",
      "streets": [
        { "name": "Bukowa", "rules": [] },
        { "name": "Wierzbowa", "rules": [
            {"from": 27, "to": null, "parity": "ODD"},
            {"from": 22, "to": null, "parity": "EVEN"}
        ]}
      ],
      "pickups": [ {"type": "ZMIESZANE", "dates": ["2026-01-14", "2026-02-11"]} ],
      "weekdayRules": []
    },
    {
      "id": "srodmiescie-1-multi",
      "name": "Śródmieście 1",
      "district": "Śródmieście",
      "houseType": "MULTI_FAMILY",
      "streets": [ {"name": "Zamkowa", "rules": []} ],
      "pickups": [],
      "weekdayRules": [
        {"type": "ZMIESZANE", "weekdays": ["MONDAY", "THURSDAY"], "weekParity": null},
        {"type": "PLASTIK",   "weekdays": ["WEDNESDAY"],          "weekParity": "EVEN"}
      ]
    }
  ]
}
```

- `houseType`: `SINGLE_FAMILY` | `MULTI_FAMILY`.
- `type`: `ZMIESZANE`, `POPIOLY`, `SEGREGOWANE`, `BIO`, `GABARYTY`, `PLASTIK`, `PAPIER`, `SZKLO`.
- `parity`: `ODD` | `EVEN` | `null` (= bez rozróżnienia).
- `from`/`to`: int albo `null` (otwarty zakres). Litery przy numerze (128B) → 128.
- `rules: []` = cała ulica należy do rejonu.
- `weekParity`: `ODD` | `EVEN` | `null`, liczone wg numeru tygodnia ISO.
- `weekdays`: angielskie nazwy `java.time.DayOfWeek`.
- Jednorodzinne wypełniają `pickups`, wielorodzinne `weekdayRules`. Nigdy oba.
- `dates` — `YYYY-MM-DD`, posortowane rosnąco.

---

## news.json

```json
{
  "generated_at": "...", "count": 60, "failures": [],
  "items": [
    {
      "id": "sha1-hasha-linku",
      "title": "Awaria wodociągu na Rudzkiej",
      "summary": "Tekst bez HTML, max ~300 znaków.",
      "link": "https://...",
      "published": "2026-09-15T08:30",
      "source": "rybnik.com.pl",
      "category": "Komunikaty",
      "priority": "ALERT"
    }
  ]
}
```

`priority`: `ALERT` (awarie, utrudnienia, ostrzeżenia — na górze listy) albo `NORMAL`.
`category` — dowolny krótki string, używany jako filtr.
Sortowanie: malejąco po `published`.

---

## sport.json

```json
{
  "generated_at": "...", "count": 58, "failures": [],
  "teams": [
    {
      "id": "row-1964",
      "name": "ROW 1964 Rybnik",
      "sport": "FOOTBALL",
      "league": "III liga, gr. III",
      "url": "http://www.90minut.pl/skarb.php?id_klub=18912"
    }
  ],
  "matches": [
    {
      "id": "row-1964-2026-09-26-stal-brzeg",
      "teamId": "row-1964",
      "competition": "III liga, Kolejka 10",
      "date": "2026-09-26",
      "time": "18:00",
      "home": "ROW 1964 Rybnik",
      "away": "Stal Brzeg",
      "isHome": true,
      "homeScore": null,
      "awayScore": null,
      "scoreNote": null,
      "status": "SCHEDULED",
      "stage": "REGULAR",
      "url": null
    }
  ]
}
```

- `sport`: `FOOTBALL` | `FOOTBALL_W` | `SPEEDWAY`.
- `status`: `SCHEDULED` | `FINISHED`. `FINISHED` zawsze ma oba wyniki, `SCHEDULED` oba `null`.
- `date` — `YYYY-MM-DD`. `time` — `HH:MM` albo **`null`**: wiosenne kolejki mają
  wyznaczoną datę, ale jeszcze nie godzinę, i nie wolno tego udawać jako 00:00.
- `isHome` — czy rybnicka drużyna jest gospodarzem. Liczone przy scrapowaniu, bo
  nazwy w źródłach są niestabilne (`INNPRO ROW Rybnik`, `ROW 1964 Rybnik`, `ROW Rybnik (k)`).
- `scoreNote` — wszystko poza gołym wynikiem: `"wo"` (walkower), `"k. 6-7"` (karne),
  `"dwumecz 87:93"` (rewanż w dwumeczu play-off, liczone z perspektywy rybnickiej drużyny).
- `stage`: `REGULAR` | `PLAYOFF` | `PLAYDOWN` | `BARRAGE` | `CUP`. Nieznana wartość spada
  w apce do `REGULAR`, a brak pola (stary cache) też oznacza `REGULAR`. Dla żużla etap
  pochodzi z `match_subtype` w danych ekstraliga.pl, dla piłki `CUP` to regionalny Puchar
  Polski („POkr"), reszta to liga.
- `url` — strona meczu albo `null`. Żużel: `https://ekstraliga.pl/se/mecz/{id}` (oficjalne
  wyniki i relacja), zawsze. Piłka mężczyzn: `http://www.90minut.pl/mecz.php?id_mecz={id}`
  (strzelcy, składy, sędzia) — **tylko HTTP**, bo 90minut nie odpowiada na 443. Pojawia się
  z opóźnieniem po meczu i nigdy dla Pucharu POkr. Piłka kobiet: **zawsze `null`**,
  90minut nie zakłada stron meczów dla III ligi kobiet, nawet za cały rozegrany sezon.
  Apka przepuszcza do przeglądarki wyłącznie adresy `http(s)://`.
  Zwykły mecz ma tu `null`.
- `teamId` wskazuje na `teams[].id`. Mecz bez pasującej drużyny apka pomija.
- Sortowanie: rosnąco po `date`, potem `time` (brak godziny idzie na koniec dnia).

---

## waste_points.json

```json
{
  "generated_at": "...", "count": 3, "failures": [],
  "points": [
    {
      "id": "pszok-kolberga",
      "name": "PSZOK Boguszowice Stare",
      "kind": "PSZOK",
      "address": "ul. Oskara Kolberga 67",
      "district": "Boguszowice Stare",
      "phone": "(32) 42 55 777",
      "email": "info@skladowisko.rybnik.pl",
      "hours": "poniedziałek - piatek: 7.00-19.00, sobota: 8.00-15.00",
      "accepted": ["papier i tektura", "metale", "szkło"],
      "note": "Odpady muszą być posegregowane...",
      "url": "https://www.rybnik.eu/..."
    }
  ],
  "guide": [
    { "item": "baterie", "where": "GPZON", "note": "Do żółtego nie wolno.", "keywords": ["bateria"] }
  ]
}
```

- `kind`: `PSZOK` | `GPZON`.
- `hours` — **surowa linia ze strony miasta**, razem z jej literówkami („piatek"). Miasto
  zapisuje godziny prozą, a każda próba normalizacji dokłada precyzję, której w źródle
  nie ma. Apka pokazuje to tak, jak stoi.
- `accepted` — lista frakcji spod nagłówka „Rodzaje … odpadów". Pusta lista oznacza, że
  strona zmieniła układ; scraper wtedy krzyczy `[warn]`, ale nie przerywa.
- `where` w `guide` MUSI być jednym z: `ZMIESZANE`, `BIO`, `PAPIER`, `SZKLO`, `PLASTIK`,
  `POPIOLY`, `GABARYTY`, `PSZOK`, `GPZON`. Nieznana wartość spada w apce do `INNE`.
- `keywords` — dodatkowe formy do wyszukiwania. Apka składa je z `item` i `note` w jeden
  worek, składa polskie znaki do ASCII i szuka po fragmencie, więc „zarowka" znajdzie
  „żarówkę".

### waste_guide_manual.json (wejście, nie wyjście)

Drugi po `manual_events.json` plik w `scraper/data/`, który **edytuje człowiek**.
Miasto publikuje zasady segregacji prozą i w PDF-ie, bez listy per przedmiot, więc
słownik „gdzie wyrzucić X" powstaje ręcznie na podstawie oficjalnych list
„wrzucamy / nie wrzucamy". `points.py` tylko go czyta i nigdy do niego nie pisze.
Wpis bez `item` albo `where` jest pomijany z ostrzeżeniem.

---

## announcement.json (wejście, edytuje człowiek)

Ogłoszenie od autora aplikacji. Żaden scraper go nie czyta ani nie pisze; `{}` znaczy
„brak ogłoszenia”. Wszystkie pola są opcjonalne, ale bez `id` i `title` nic się nie
pokazuje.

| Pole | Typ | Znaczenie |
|---|---|---|
| `id` | string | unikalne dla każdego ogłoszenia; po nim apka pamięta „powiadomiono” i „zamknięto” |
| `title` | string | tytuł karty i powiadomienia |
| `body` | string? | treść |
| `link` | string? | tylko `http(s)://`, inne schematy są odrzucane |
| `link_label` | string? | napis na przycisku, domyślnie „Otwórz” |
| `notify` | bool | domyślnie `true`; `false` = sama karta, bez powiadomienia |
| `min_version`, `max_version` | int? | zakres versionCode włącznie |
| `until` | `YYYY-MM-DD`? | ostatni dzień wyświetlania; niepoprawna data = ukryte |

---

## transit_meta.json

Rozkład jest za duży na JSON — apka pobiera `gtfs.zip` bezpośrednio.
Ten plik tylko wskazuje, gdzie leży aktualne wydanie (ID załącznika zmienia się
co edycję, więc trzeba je rozwiązywać przez scraping strony z plikami).

```json
{
  "generated_at": "...",
  "gtfs_url": "https://km.rybnik.pl/download/attachment/633/gtfs.zip",
  "attachment_id": 633,
  "valid_from": "2026-09-01",
  "valid_to": "2026-12-31",
  "stop_count": 626,
  "route_count": 44,
  "sha256": "..."
}
```
