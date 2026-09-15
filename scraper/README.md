# Scraper

Fetches upcoming events in Rybnik (theatre, gigs, clubs, domy kultury, sport) and writes them to
`data/events.json` in a format the Android app can consume.

## Run locally

```bash
cd scraper
python -m venv .venv && source .venv/bin/activate  # PowerShell: .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python scraper.py
```

Output: `scraper/data/events.json`.

## Run in CI

`.github/workflows/scrape.yml` runs this every 6 hours and commits the updated `events.json` back to `main`. The Android app pulls that file over HTTPS from `raw.githubusercontent.com`.

## Other scrapers

| Script | Output | What it does |
|---|---|---|
| `scraper.py` | `data/events.json` | Events from TZR, iRybnik, biletyna.pl, DK Boguszowice/Chwałowice/Niedobczyce and ROW Rybnik (speedway). Merged and deduplicated. |
| `news.py` | `data/news.json` | News from rybnik.com.pl, turybnik.pl, nowiny.pl (RSS) + rybnik.eu (HTML, no feed exists). Sets `priority: ALERT/NORMAL` — tune `ALERT_KEYWORDS` in the file. |
| `transit.py` | `data/transit_meta.json` | Resolves the current KM Rybnik GTFS attachment id, downloads the zip to verify it (never committed), records validity window + sha256. Prints the three known feed quirks as `[warn]`. |

All three depend only on `requirements.txt` (requests + beautifulsoup4); the rest
is stdlib.

## Adding more sources

Each source in `scraper.py` is a `scrape_*()` returning `list[Event]`; register it in the
`SOURCES` list and `main()` does the rest. A source that raises is recorded in `failures`
and never aborts the run — partial data beats no data.

Preferred input formats, in order: iCal (`?ical=1` on WordPress "The Events Calendar"),
schema.org JSON-LD (`<script type="application/ld+json">`), then HTML scraping.

Deduplication happens in `deduplicate()` — sources overlap heavily (biletyna and iRybnik
both relist TZR). The key is `(normalised title, start date)`, with two extra passes for
decorated titles; the copy with the richest description wins. Classification is shared:
extend `CATEGORY_RULES` in one place rather than per parser.
