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
`start`/`end` — ISO local bez strefy, minutowa precyzja. `id` — globalnie unikalne,
prefiks źródła (`tzr-`, `iryb-`, `bilnya-`, `dk-`, `row-`).

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
