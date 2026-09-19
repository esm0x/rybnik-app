#!/usr/bin/env python3
"""Scrape upcoming events in Rybnik → scraper/data/events.json

Sources (each one is an independent `scrape_*()` returning list[Event]; a source
that blows up is recorded in `failures` and never aborts the run):

  TZR       teatrziemirybnickiej.pl/wydarzenia  — HTML detail pages
  iRybnik   irybnik.pl/wydarzenie               — HTML list + per-event JSON-LD
  biletyna  biletyna.pl/Rybnik                  — schema.org JSON-LD ItemList
  DK        dkboguszowice / dkchwalowice / dkniedobczyce — iCal (?ical=1) or HTML
  ROW       row.rybnik.com.pl/druzyna/terminarz — speedway fixture table

Everything is merged in main(), deduplicated on (normalised title, start date)
and written as one payload.

Category strings emitted here MUST match Android's EventCategory enum names
(see CONTRACT.md).
"""

from __future__ import annotations

import json
import re
import sys
import time
import unicodedata
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Callable, Iterable, Optional
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)
TIMEOUT = 30

VALID_CATEGORIES = {
    "Koncert", "Spektakl", "Kabaret", "Film", "Festiwal", "Wystawa",
    "Warsztaty", "Impreza", "Sport", "DlaDzieci", "Inne",
}

# Lower = preferred when two sources describe the same event with descriptions
# of equal length (the primary key is description richness).
SOURCE_RANK = {"TZR": 0, "biletyna.pl": 1, "iRybnik": 3}


@dataclass
class Event:
    id: str
    title: str
    category: str
    start: str        # ISO 8601 local time (Europe/Warsaw implied)
    end: Optional[str]
    venue: str
    description: Optional[str]
    sourceName: str
    sourceUrl: str


# --------------------------------------------------------------------------- #
# HTTP / text helpers
# --------------------------------------------------------------------------- #

def http_get(url: str, session: Optional[requests.Session] = None) -> str:
    getter = session.get if session else requests.get
    r = getter(url, headers={"User-Agent": UA}, timeout=TIMEOUT)
    r.raise_for_status()
    # Some of these servers omit the charset; requests then falls back to
    # latin-1 and mangles every Polish diacritic.
    if (r.encoding or "").lower() in ("iso-8859-1", "latin-1", "latin_1"):
        try:
            r.content.decode("utf-8")
            r.encoding = "utf-8"
        except UnicodeDecodeError:
            pass
    return r.text


def get_soup(url: str, session: Optional[requests.Session] = None) -> BeautifulSoup:
    return BeautifulSoup(http_get(url, session), "html.parser")


def clean_text(s: Optional[str]) -> Optional[str]:
    if not s:
        return None
    s = " ".join(s.split())
    return s or None


def strip_accents(s: str) -> str:
    s = s.replace("ł", "l").replace("Ł", "L")
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def normalise_title(title: str) -> str:
    """Lowercase, drop accents and punctuation, collapse whitespace — dedup key."""
    s = strip_accents(title).lower()
    s = re.sub(r"[^\w\s]+", " ", s, flags=re.UNICODE)
    return " ".join(s.split())


def slugify(s: str, max_len: int = 60) -> str:
    s = strip_accents(s).lower()
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s[:max_len].strip("-") or "event"


def iso(dt: datetime) -> str:
    return dt.replace(second=0, microsecond=0).isoformat(timespec="minutes")


def parse_iso_local(value: str) -> datetime:
    """'2026-10-16T18:00:00+02:00' → naive local datetime (offsets here are already
    Europe/Warsaw, so dropping the tzinfo keeps the correct wall-clock time)."""
    dt = datetime.fromisoformat(value.strip())
    return dt.replace(tzinfo=None)


# --------------------------------------------------------------------------- #
# Shared classification — one place, so every source agrees
# --------------------------------------------------------------------------- #

# Ordered: first rule whose pattern matches wins.
# A title that says "for kids" outright wins over everything else: a Halloween
# party for toddlers belongs in DlaDzieci, not Impreza. Deliberately checked on
# the *title* only — source labels and descriptions mention children far too
# loosely ("zapraszamy dzieci") to be trusted with this.
KIDS_TITLE_RX = (r"dla dzieci|dla najmlodsz|dla najmłodsz|dla maluch|dla przedszkolak|"
                 r"najnaj|\bkids\b|\bbaby\b|dzieciom|dla rodzin|familijn|dla calej rodziny|"
                 r"dla całej rodziny")

CATEGORY_RULES: list[tuple[str, str]] = [
    ("Impreza", r"\bimprez|potancowk|potańcówk|potancowk|dyskotek|\bdj\b|dj set|dj-set|"
                r"techno|house music|\brave\b|silent\s?disco|\bdisco\b|after\s?party|\bparty\b|\bnight\b|"
                r"parkiet|zabawa taneczna|sylwester|andrzejk|halloween|juwenalia|"
                r"klubow|do bialego rana|do białego rana|\bbal\b|sub-?club"),
    ("Sport", r"zuzl|zuzel|żużl|żużel|speedway|row rybnik|ekstraliga|\bliga\b|\bmecz\b|"
              r"turniej|\bbieg\b|biegow|maraton|\bmtb\b|rowerow|zawody|wyscig|wyścig|"
              r"crossfit|\bfit\b|fitness|games rx|paintball|wspinacz|\brace\b|extreme|"
              r"\brajd\b|sparing|puchar\b"),
    ("Kabaret", r"kabaret|stand-?\s?up|standup"),
    ("Koncert", r"\bkoncert|recital|unplugged|akustyczn|live music|jam session|"
                r"przeglad kapel|przegląd kapel|\btour\b|zagraj[aą]|\bgig\b|piosenk"),
    ("Film", r"\bfilm|\bkino\b|seans|projekcja|\bdkf\b|screening|kinow"),
    ("Wystawa", r"wystaw|wernisa|galeri|ekspozycj|malarstw"),
    ("Warsztaty", r"warsztat|zaj[eę]cia|\bkurs\b|szkolen|lekcj|master ?class|"
                  r"spotkanie autorskie|prelekcj|\bwyklad|\bwykład"),
    ("Spektakl", r"spektakl|teatr|musical|operetk|\bopera\b|\bbalet|monodram|"
                 r"przedstawien|widowisk|komedia"),
    ("Festiwal", r"festiwal|festival|jarmark|\bfest\b|przegl[aą]d"),
    ("DlaDzieci", r"dzieci|najmlodsz|najmłodsz|bajk|familijn|rodzinn|maluch|"
                  r"przedszkol|teatrzyk|\bferie\b|poranek"),
]

# Venues whose events default to nightlife when nothing else classifies them.
CLUB_VENUES = r"odyseja|sub-?club|stacja b\.|hamulec|celtic"


def _first_match(haystack: str) -> Optional[str]:
    for category, pattern in CATEGORY_RULES:
        if re.search(pattern, haystack, re.IGNORECASE):
            return category
    return None


def classify(
    title: str,
    description: Optional[str] = None,
    source_hint: Optional[str] = None,
    venue: Optional[str] = None,
) -> str:
    """Map a free-text event onto the EventCategory enum.

    Two passes: the title (plus whatever label the source gave us) is trusted
    first, the long description only as a fallback — descriptions are noisy and
    a concert blurb mentioning "dzieci" should not become DlaDzieci.
    """
    if re.search(KIDS_TITLE_RX, strip_accents(title).lower() + " " + title.lower(), re.IGNORECASE):
        return "DlaDzieci"

    primary = " ".join(x for x in (title, source_hint) if x)
    category = _first_match(strip_accents(primary).lower() + " " + primary.lower())
    if not category and description:
        category = _first_match(strip_accents(description).lower() + " " + description.lower())
    if not category:
        category = "Inne"
    if category == "Inne" and venue and re.search(CLUB_VENUES, venue, re.IGNORECASE):
        category = "Impreza"
    return category


# --------------------------------------------------------------------------- #
# Shared venue detection
# --------------------------------------------------------------------------- #

VENUE_PATTERNS: list[tuple[str, str]] = [
    (r"fundacj\w*\s+elektrowni|elektrownia rybnik", "Sala widowiskowa Fundacji Elektrowni Rybnik"),
    (r"klub\w*\s+odysej|odyseja", "Klub Odyseja"),
    (r"sub-?club", "Sub-Club"),
    (r"stacja b\.", "Stacja B."),
    (r"teatr\w*\s+ziemi\s+rybnick", "Teatr Ziemi Rybnickiej"),
    (r"boguszowic", "Dom Kultury Boguszowice"),
    (r"chwalowic|chwałowic", "Dom Kultury Rybnik-Chwałowice"),
    (r"niedobczyc", "Dom Kultury Niedobczyce"),
    (r"bazylik\w*\s+(?:sw\.|św\.)?\s*antoniego", "Bazylika św. Antoniego w Rybniku"),
    (r"halo!?\s*rybnik", "Halo! Rybnik"),
    (r"stadion\w*\s+miejsk|stadion\w*\s+im\.|stadion\s+żużlow", "Stadion Miejski w Rybniku"),
    (r"biblioteka|pmbp", "Powiatowa i Miejska Biblioteka Publiczna w Rybniku"),
    (r"muzeum", "Muzeum w Rybniku"),
    (r"kampus", "Kampus w Rybniku"),
]

CHURCH_RX = re.compile(
    r"kości[oe]?[łl]\w*\s+(?:pw\.\s*)?((?:św\.|Św\.)\s*[\wąćęłńóśźż]+(?:\s+[\wąćęłńóśźż]+)?)",
    re.IGNORECASE,
)


def detect_venue(text: str, default: Optional[str] = None) -> Optional[str]:
    """Pull a real venue name out of a title/description blob."""
    if not text:
        return default
    for pattern, name in VENUE_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE):
            return name
    m = CHURCH_RX.search(text)
    if m:
        return "Kościół " + " ".join(m.group(1).split())
    return default


# --------------------------------------------------------------------------- #
# Source 1: Teatr Ziemi Rybnickiej (unchanged logic, richer venue/category)
# --------------------------------------------------------------------------- #

TZR_BASE = "https://www.teatrziemirybnickiej.pl"
TZR_LIST_URL = f"{TZR_BASE}/wydarzenia"

# TZR prints its own category label above the <h1>; we forward it to classify()
# as a hint rather than trusting it blindly.
TZR_LABELS = (
    "koncert", "spektakl", "kabaret", "stand-up", "film", "festiwal", "wystawa",
    "warsztaty", "musical", "operetka", "opera", "balet", "widowisko", "taniec",
    "jam session", "dla dzieci", "wydarzenie plenerowe", "inne",
)

# Matches BOTH: "18.09.2026, godz. 19:00" AND "18.09.2026, godz. 19.00"
# (list page uses colon, detail page uses dot — go figure)
TZR_DATE_RX = re.compile(
    r"(\d{1,2})\.(\d{1,2})\.(\d{4})\s*,?\s*godz\.\s*(\d{1,2})[:\.](\d{2})",
    re.IGNORECASE,
)


def tzr_parse_dt(m: re.Match) -> datetime:
    d, mo, y, h, mi = m.groups()
    return datetime(int(y), int(mo), int(d), int(h), int(mi))


def tzr_collect_urls(html: str) -> list[str]:
    soup = BeautifulSoup(html, "html.parser")
    urls: list[str] = []
    seen: set[str] = set()
    for a in soup.select("a[href]"):
        href = a["href"]
        if "/wydarzenia/" not in href or not href.endswith(".html"):
            continue
        full = urljoin(TZR_BASE, href)
        if full.rstrip("/") == TZR_LIST_URL.rstrip("/"):
            continue
        if full in seen:
            continue
        seen.add(full)
        urls.append(full)
    return urls


def tzr_detect_label(soup: BeautifulSoup, h1) -> Optional[str]:
    """The category label sits in a plain text/element BEFORE the h1, e.g. 'Koncert'."""
    parts: list[str] = []
    for node in h1.find_all_previous(string=True, limit=40):
        s = node.strip()
        if s:
            parts.append(s.lower())
    haystack = " ".join(parts)
    for label in TZR_LABELS:
        if re.search(rf"\b{re.escape(label)}\b", haystack):
            return label
    return None


def tzr_detect_description(h1) -> Optional[str]:
    """First <p> after the h1 with meaningful length."""
    for p in h1.find_all_next("p", limit=15):
        text = p.get_text(" ", strip=True)
        if len(text) >= 40 and "godz." not in text.lower():
            return text
    return None


def parse_tzr_detail(url: str) -> Optional[Event]:
    soup = BeautifulSoup(http_get(url), "html.parser")

    h1 = soup.find("h1")
    if not h1:
        print(f"[warn] no <h1> at {url}", file=sys.stderr)
        return None
    # TZR glues a cycle label into the heading: <h1>Title<span class="info">dkf ekran</span></h1>
    for extra in h1.select("span.info"):
        extra.decompose()
    title = " ".join(h1.get_text(" ", strip=True).split())

    label = tzr_detect_label(soup, h1)
    description = tzr_detect_description(h1)

    # Collect all datetime mentions on the page and dedupe
    body_text = soup.get_text(" ", strip=True)
    matches = list(TZR_DATE_RX.finditer(body_text))
    if not matches:
        print(f"[warn] no dates at {url}", file=sys.stderr)
        return None
    dts = sorted({tzr_parse_dt(m) for m in matches})

    # Most TZR events happen in the theatre, but some are hosted elsewhere
    # (Fundacja Elektrowni, churches) — the title/description usually says so.
    venue = detect_venue(f"{title} {description or ''}", "Teatr Ziemi Rybnickiej")

    return Event(
        id="tzr-" + url.rstrip("/").split("/")[-1].removesuffix(".html"),
        title=title,
        category=classify(title, description, label, venue),
        start=iso(dts[0]),
        end=iso(dts[-1]) if len(dts) > 1 else None,
        venue=venue or "Teatr Ziemi Rybnickiej",
        description=description,
        sourceName="TZR",
        sourceUrl=url,
    )


def scrape_tzr() -> list[Event]:
    print(f"[info] TZR: fetching list {TZR_LIST_URL}", file=sys.stderr)
    urls = tzr_collect_urls(http_get(TZR_LIST_URL))
    print(f"[info] TZR: {len(urls)} event links", file=sys.stderr)

    events: list[Event] = []
    for i, url in enumerate(urls, 1):
        try:
            ev = parse_tzr_detail(url)
            if ev:
                events.append(ev)
        except Exception as e:  # noqa: BLE001 — one broken detail page is not fatal
            print(f"[error] TZR {url}: {e}", file=sys.stderr)
        if i % 10 == 0:
            print(f"[info] TZR: {i}/{len(urls)}", file=sys.stderr)
        time.sleep(0.3)  # be nice to the server
    return events


# --------------------------------------------------------------------------- #
# Source 2: iRybnik — the nightlife/gig feed
# --------------------------------------------------------------------------- #

IRYBNIK_BASE = "https://irybnik.pl"
IRYBNIK_LIST_URL = f"{IRYBNIK_BASE}/wydarzenie/"


def irybnik_ld_events(soup: BeautifulSoup) -> list[dict]:
    """All schema.org Event objects embedded in a page (list or detail)."""
    out: list[dict] = []
    for script in soup.find_all("script", type="application/ld+json"):
        raw = script.string or script.get_text()
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            continue
        blobs = data if isinstance(data, list) else [data]
        for blob in blobs:
            if not isinstance(blob, dict):
                continue
            if blob.get("@type") == "ItemList":
                for item in blob.get("itemListElement", []):
                    inner = item.get("item") if isinstance(item, dict) else None
                    if isinstance(inner, dict) and "Event" in str(inner.get("@type", "")):
                        out.append(inner)
            elif "Event" in str(blob.get("@type", "")):
                out.append(blob)
    return out


def irybnik_collect_listings(soup: BeautifulSoup) -> list[dict]:
    """Every event row on the listing page (cards = upcoming, month sections also
    contain past ones — those get dropped by the future filter later)."""
    rows: dict[str, dict] = {}
    for art in soup.select("article.event-card, article.event-item"):
        link = art.select_one("a.event-link[href]")
        time_el = art.select_one("time[datetime]")
        if not link or not time_el:
            continue
        url = urljoin(IRYBNIK_BASE, link["href"])
        desc = art.select_one(".event-description")
        rows.setdefault(url, {
            "url": url,
            "title": clean_text(link.get_text(" ", strip=True)) or "",
            "start": time_el["datetime"],
            "description": clean_text(desc.get_text(" ", strip=True)) if desc else None,
        })
    return list(rows.values())


def irybnik_venue(ld: dict, title: str, description: str) -> str:
    location = ld.get("location") or {}
    address = clean_text(location.get("name")) if isinstance(location, dict) else None
    organizer = ld.get("organizer") or {}
    org = clean_text(organizer.get("name")) if isinstance(organizer, dict) else None
    if org:
        org = re.sub(r"\s*[-–]\s*(wydarzenia|events)$", "", org, flags=re.IGNORECASE)

    name = detect_venue(f"{title} {description} {address or ''}") or (
        org if org and org.lower() not in ("rybnik", "irybnik", "irybnik.pl") else None
    )
    if address and address.strip().lower() in ("rybnik", "pl", ""):
        address = None
    if name and address and normalise_title(name) not in normalise_title(address):
        return f"{name}, {address}"
    return name or address or "Rybnik"


def parse_irybnik_detail(row: dict, session: requests.Session) -> Event:
    """Enrich a listing row with the detail page (full text + real address)."""
    title = row["title"]
    description = row.get("description")
    venue = None
    try:
        soup = BeautifulSoup(http_get(row["url"], session), "html.parser")
        body = soup.select_one("article.article-content")
        if body:
            paragraphs = [
                t for p in body.find_all("p")
                if (t := p.get_text(" ", strip=True))
                and len(t) >= 40
                and not t.lower().startswith(("autor:", "link do wydarzenia"))
            ]
            full = " ".join(paragraphs[:4])
            if full and len(full) > len(description or ""):
                description = clean_text(full)[:1500]
        lds = irybnik_ld_events(soup)
        if lds:
            venue = irybnik_venue(lds[0], title, description or "")
            if lds[0].get("startDate"):
                row["start"] = lds[0]["startDate"]
    except Exception as e:  # noqa: BLE001 — fall back to the listing data
        print(f"[warn] iRybnik detail {row['url']}: {e}", file=sys.stderr)

    if not venue:
        venue = detect_venue(f"{title} {description or ''}", "Rybnik") or "Rybnik"
    start = parse_iso_local(row["start"])
    return Event(
        id="iryb-" + slugify(row["url"].rstrip("/").split("/")[-1]),
        title=title,
        category=classify(title, description, "iRybnik", venue),
        start=iso(start),
        end=None,
        venue=venue,
        description=description,
        sourceName="iRybnik",
        sourceUrl=row["url"],
    )


def scrape_irybnik() -> list[Event]:
    session = requests.Session()
    print(f"[info] iRybnik: fetching {IRYBNIK_LIST_URL}", file=sys.stderr)
    soup = BeautifulSoup(http_get(IRYBNIK_LIST_URL, session), "html.parser")
    rows = irybnik_collect_listings(soup)

    # The month sections also list events that already happened; skip them here
    # so we do not fetch ~130 useless detail pages.
    cutoff = datetime.now() - timedelta(hours=3)
    rows = [r for r in rows if parse_iso_local(r["start"]) >= cutoff]
    print(f"[info] iRybnik: {len(rows)} upcoming listings", file=sys.stderr)

    events: list[Event] = []
    for i, row in enumerate(rows, 1):
        try:
            events.append(parse_irybnik_detail(row, session))
        except Exception as e:  # noqa: BLE001
            print(f"[error] iRybnik {row.get('url')}: {e}", file=sys.stderr)
        if i % 20 == 0:
            print(f"[info] iRybnik: {i}/{len(rows)}", file=sys.stderr)
        time.sleep(0.25)
    return events


# --------------------------------------------------------------------------- #
# Source 3: biletyna.pl — schema.org JSON-LD
# --------------------------------------------------------------------------- #

BILETYNA_URL = "https://biletyna.pl/Rybnik"

# schema.org subtype → hint word fed to classify()
BILETYNA_TYPE_HINT = {
    "MusicEvent": "koncert",
    "TheaterEvent": "spektakl",
    "ScreeningEvent": "film seans",
    "ChildrensEvent": "dla dzieci",
    "ComedyEvent": "kabaret",
    "DanceEvent": "impreza taneczna",
    "Festival": "festiwal",
    "ExhibitionEvent": "wystawa",
    "SportsEvent": "mecz sport",
    "EducationEvent": "warsztaty",
}

# biletyna appends a boilerplate sentence to every description
BILETYNA_BOILERPLATE = re.compile(
    r"\s*Wydarzenie odbędzie się w .*?(?:o godz\.\s*\d{1,2}[:.]\d{2})\.?\s*$", re.IGNORECASE
)


def biletyna_ld_items(soup: BeautifulSoup) -> list[dict]:
    items: list[dict] = []
    for script in soup.find_all("script", type="application/ld+json"):
        raw = script.string or script.get_text()
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as e:
            print(f"[warn] biletyna: bad JSON-LD ({e})", file=sys.stderr)
            continue
        if isinstance(data, dict) and data.get("@type") == "ItemList":
            for entry in data.get("itemListElement", []):
                item = entry.get("item") if isinstance(entry, dict) else None
                if isinstance(item, dict) and item.get("startDate"):
                    items.append(item)
    return items


def parse_biletyna_item(item: dict) -> Optional[Event]:
    title = clean_text(item.get("name"))
    start_raw = item.get("startDate")
    if not title or not start_raw:
        return None
    start = parse_iso_local(start_raw)
    end = parse_iso_local(item["endDate"]) if item.get("endDate") else None

    location = item.get("location") or {}
    venue = clean_text(location.get("name")) if isinstance(location, dict) else None
    address = location.get("address") if isinstance(location, dict) else None
    street = clean_text(address.get("streetAddress")) if isinstance(address, dict) else None
    if venue and street:
        venue = f"{venue}, {street}"
    venue = venue or detect_venue(title, "Rybnik") or "Rybnik"

    description = clean_text(item.get("description"))
    if description:
        description = clean_text(BILETYNA_BOILERPLATE.sub("", description))

    hint = BILETYNA_TYPE_HINT.get(str(item.get("@type")), "")
    url = item.get("url") or BILETYNA_URL

    return Event(
        id=f"bilnya-{slugify(title)}-{start:%Y%m%d%H%M}",
        title=title,
        category=classify(title, description, hint, venue),
        start=iso(start),
        end=iso(end) if end else None,
        venue=venue,
        description=description,
        sourceName="biletyna.pl",
        sourceUrl=url,
    )


def scrape_biletyna() -> list[Event]:
    session = requests.Session()
    seen: dict[tuple[str, str], Event] = {}
    empty_pages = 0
    # Paging on biletyna is server-side and a bit stateful: identical requests can
    # return the same slice a few times before it advances. Walk a handful of pages
    # and stop once nothing new shows up.
    for page in range(1, 9):
        url = BILETYNA_URL if page == 1 else f"{BILETYNA_URL}?page={page}"
        soup = BeautifulSoup(http_get(url, session), "html.parser")
        items = biletyna_ld_items(soup)
        new = 0
        for item in items:
            ev = parse_biletyna_item(item)
            if not ev:
                continue
            key = (normalise_title(ev.title), ev.start)
            if key not in seen:
                seen[key] = ev
                new += 1
        print(f"[info] biletyna: page {page} → {len(items)} items, {new} new", file=sys.stderr)
        empty_pages = empty_pages + 1 if new == 0 else 0
        if empty_pages >= 3:
            break
        time.sleep(0.4)
    return list(seen.values())


# --------------------------------------------------------------------------- #
# Source 4: Domy Kultury — iCal first, HTML fallback
# --------------------------------------------------------------------------- #

def unfold_ics(text: str) -> list[str]:
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out: list[str] = []
    for line in lines:
        if line[:1] in (" ", "\t") and out:
            out[-1] += line[1:]
        else:
            out.append(line)
    return out


def ics_unescape(value: str) -> str:
    return (value.replace("\\n", "\n").replace("\\N", "\n")
                 .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\"))


def parse_ics(text: str) -> list[dict[str, tuple[str, str]]]:
    """Minimal VEVENT reader → [{KEY: (value, raw_params)}]."""
    events: list[dict[str, tuple[str, str]]] = []
    current: Optional[dict[str, tuple[str, str]]] = None
    for line in unfold_ics(text):
        stripped = line.strip()
        if stripped == "BEGIN:VEVENT":
            current = {}
        elif stripped == "END:VEVENT":
            if current is not None:
                events.append(current)
            current = None
        elif current is not None and ":" in line:
            name, _, value = line.partition(":")
            key, _, params = name.partition(";")
            current[key.strip().upper()] = (value, params)
    return events


def parse_ics_dt(value: str, params: str) -> datetime:
    v = value.strip()
    if "VALUE=DATE" in params.upper() or len(v) == 8:
        return datetime.strptime(v[:8], "%Y%m%d")
    if v.endswith("Z"):
        dt = datetime.strptime(v, "%Y%m%dT%H%M%SZ")
        try:
            from zoneinfo import ZoneInfo
            return dt.replace(tzinfo=ZoneInfo("UTC")).astimezone(ZoneInfo("Europe/Warsaw")).replace(tzinfo=None)
        except Exception:  # noqa: BLE001 — no tzdata on the runner
            return dt + timedelta(hours=2)
    return datetime.strptime(v[:15], "%Y%m%dT%H%M%S")


# name, listing url, id prefix, venue
DK_SOURCES = [
    ("DK Boguszowice", "https://dkboguszowice.pl/wydarzenia/", "dk-bog", "Dom Kultury Boguszowice"),
    ("DK Chwałowice", "https://www.dkchwalowice.pl/", "dk-chw", "Dom Kultury Rybnik-Chwałowice"),
    ("DK Niedobczyce", "https://dkniedobczyce.pl/terminarz/", "dk-nie", "Dom Kultury Niedobczyce"),
]

PL_MONTHS = {
    "stycznia": 1, "lutego": 2, "marca": 3, "kwietnia": 4, "maja": 5, "czerwca": 6,
    "lipca": 7, "sierpnia": 8, "września": 9, "wrzesnia": 9, "października": 10,
    "pazdziernika": 10, "listopada": 11, "grudnia": 12,
}


def parse_dk_ical(source: str, url: str, prefix: str, default_venue: str) -> list[Event]:
    """The Events Calendar exposes <listing>?ical=1. Returns [] if it does not."""
    sep = "&" if "?" in url else "?"
    r = requests.get(f"{url}{sep}ical=1", headers={"User-Agent": UA}, timeout=TIMEOUT)
    if r.status_code != 200 or "text/calendar" not in r.headers.get("content-type", ""):
        return []
    text = r.content.decode("utf-8", errors="replace")
    events: list[Event] = []
    for raw in parse_ics(text):
        if "DTSTART" not in raw or "SUMMARY" not in raw:
            continue
        title = clean_text(ics_unescape(raw["SUMMARY"][0]))
        if not title:
            continue
        start = parse_ics_dt(*raw["DTSTART"])
        end = parse_ics_dt(*raw["DTEND"]) if "DTEND" in raw else None
        description = clean_text(ics_unescape(raw["DESCRIPTION"][0])) if "DESCRIPTION" in raw else None
        link = (raw.get("URL") or (url, ""))[0].strip() or url
        hint = ics_unescape(raw["CATEGORIES"][0]) if "CATEGORIES" in raw else ""
        venue = detect_venue(f"{title} {description or ''}", default_venue) or default_venue
        uid = (raw.get("UID") or ("", ""))[0].split("@")[0].split("-")[0]
        events.append(Event(
            id=f"{prefix}-{slugify(uid + '-' + title, 70)}",
            title=title,
            category=classify(title, description, hint, venue),
            start=iso(start),
            end=iso(end) if end else None,
            venue=venue,
            description=description,
            sourceName=source,
            sourceUrl=link,
        ))
    return events


def parse_dkchwalowice_html(source: str, url: str, prefix: str, default_venue: str) -> list[Event]:
    """Fallback: the news grid, where dated items carry '| DD.MM.YYYY' in the title."""
    soup = get_soup(url)
    events: list[Event] = []
    for block in soup.select("div.news"):
        link = block.select_one(".title a[href]")
        if not link:
            continue
        title_raw = clean_text(link.get_text(" ", strip=True)) or ""
        body = block.select_one(".header")
        description = clean_text(body.get_text(" ", strip=True)) if body else None
        blob = f"{title_raw} {description or ''}"

        m = re.search(r"\|\s*(\d{1,2})\.(\d{1,2})\.(\d{4})", title_raw)
        if m:
            day, month, year = (int(x) for x in m.groups())
        else:
            m2 = re.search(r"(\d{1,2})\s+([a-ząćęłńóśźż]+)\s+(\d{4})", blob, re.IGNORECASE)
            if not m2 or m2.group(2).lower() not in PL_MONTHS:
                continue
            day, month, year = int(m2.group(1)), PL_MONTHS[m2.group(2).lower()], int(m2.group(3))
        tm = re.search(r"(?:godz\.\s*|\bo\s+)(\d{1,2})[:.](\d{2})", blob)
        hour, minute = (int(tm.group(1)), int(tm.group(2))) if tm else (0, 0)
        try:
            start = datetime(year, month, day, hour, minute)
        except ValueError:
            continue

        title = re.sub(r"\s*\|\s*\d{1,2}\.\d{1,2}\.\d{4}\s*$", "", title_raw).strip()
        venue = detect_venue(blob, default_venue) or default_venue
        events.append(Event(
            id=f"{prefix}-{slugify(title)}-{start:%Y%m%d}",
            title=title,
            category=classify(title, description, source, venue),
            start=iso(start),
            end=None,
            venue=venue,
            description=description,
            sourceName=source,
            sourceUrl=urljoin(url, link["href"]),
        ))
    return events


def parse_dkniedobczyce_html(source: str, url: str, prefix: str, default_venue: str) -> list[Event]:
    """Fallback: the /terminarz/ table (Nazwa | Data | Uwagi)."""
    soup = get_soup(url)
    events: list[Event] = []
    for row in soup.select("table tr"):
        cells = [clean_text(td.get_text(" ", strip=True)) or "" for td in row.find_all("td")]
        if len(cells) < 2:
            continue
        title, date_cell = cells[0], cells[1]
        m = re.search(r"(\d{1,2})(?:-\d{1,2})?\.(\d{1,2})\.(\d{4})", date_cell)
        if not title or not m:
            continue
        try:
            start = datetime(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        except ValueError:
            continue
        note = cells[2] if len(cells) > 2 else None
        venue = detect_venue(f"{title} {note or ''}", default_venue) or default_venue
        events.append(Event(
            id=f"{prefix}-{slugify(title)}-{start:%Y%m%d}",
            title=title,
            category=classify(title, note, source, venue),
            start=iso(start),
            end=None,
            venue=venue,
            description=note,
            sourceName=source,
            sourceUrl=url,
        ))
    return events


DK_HTML_FALLBACKS = {
    "DK Chwałowice": parse_dkchwalowice_html,
    "DK Niedobczyce": parse_dkniedobczyce_html,
}


def make_dk_scraper(source: str, url: str, prefix: str, venue: str) -> Callable[[], list[Event]]:
    def scrape() -> list[Event]:
        events = parse_dk_ical(source, url, prefix, venue)
        if events:
            print(f"[info] {source}: {len(events)} events from iCal", file=sys.stderr)
            return events
        fallback = DK_HTML_FALLBACKS.get(source)
        if not fallback:
            return []
        events = fallback(source, url, prefix, venue)
        print(f"[info] {source}: no iCal, {len(events)} events from HTML", file=sys.stderr)
        return events
    scrape.__name__ = f"scrape_{prefix.replace('-', '_')}"
    return scrape


# --------------------------------------------------------------------------- #
# Source 5: ROW Rybnik speedway
# --------------------------------------------------------------------------- #

ROW_URL = "https://row.rybnik.com.pl/druzyna/terminarz"
ROW_VENUE = "Stadion Miejski w Rybniku, ul. Gliwicka 72"
ROW_DATE_RX = re.compile(r"(\d{1,2})\.(\d{1,2})\.(\d{4})\s*(?:(\d{1,2})[:.](\d{2}))?")


def scrape_row() -> list[Event]:
    """Speedway fixtures. Season-bound: an empty table off-season is fine."""
    soup = get_soup(ROW_URL)
    events: list[Event] = []
    for row in soup.select("table tr"):
        date_cell = row.select_one("td.timetable + td.timetable") or None
        teams = [clean_text(td.get_text(" ", strip=True)) or "" for td in row.select("td.timetable-team")]
        if not date_cell or len(teams) < 2:
            continue
        m = ROW_DATE_RX.search(date_cell.get_text(" ", strip=True))
        if not m:
            continue
        day, month, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
        hour = int(m.group(4)) if m.group(4) else 0
        minute = int(m.group(5)) if m.group(5) else 0
        try:
            start = datetime(year, month, day, hour, minute)
        except ValueError:
            continue
        host, guest = teams[0], teams[1]
        home = "rybnik" in host.lower()
        title = f"Żużel: {host} – {guest}"
        round_cell = row.select_one("td.timetable")
        rnd = slugify(round_cell.get_text(" ", strip=True)) if round_cell else "runda"
        events.append(Event(
            id=f"row-{rnd}-{start:%Y%m%d}",
            title=title,
            category="Sport",
            start=iso(start),
            end=None,
            venue=ROW_VENUE if home else f"wyjazd: {host}",
            description=f"Mecz {'domowy' if home else 'wyjazdowy'} żużlowej ligi — {host} kontra {guest}.",
            sourceName="ROW Rybnik",
            sourceUrl=ROW_URL,
        ))
    print(f"[info] ROW: {len(events)} fixtures in the table", file=sys.stderr)
    return events


# --------------------------------------------------------------------------- #
# Merge / dedupe
# --------------------------------------------------------------------------- #

def _richness(ev: Event) -> tuple[int, int]:
    """Higher is better: longer description first, then source preference."""
    return (len(ev.description or ""), -SOURCE_RANK.get(ev.sourceName, 2))


def _merge_venue(keep: Event, drop: Event) -> None:
    generic = ("", "rybnik")
    if keep.venue.strip().lower() in generic and drop.venue.strip().lower() not in generic:
        keep.venue = drop.venue
    if keep.end is None and drop.end is not None:
        keep.end = drop.end


# Words that carry no identity — ignored when comparing two titles.
TITLE_STOPWORDS = {
    "w", "we", "z", "za", "i", "na", "do", "o", "od", "po", "przy", "oraz", "a",
    "the", "czyli", "vol", "dla", "rybnik", "rybniku", "rybnicki", "rybnickim",
    "koncert", "koncerty", "spektakl", "wydarzenie", "bilety",
}


def significant_tokens(title: str) -> set[str]:
    return {t for t in normalise_title(title).split() if len(t) > 2 and t not in TITLE_STOPWORDS}


def deduplicate(events: list[Event]) -> tuple[list[Event], int]:
    """Key: (normalised title, start date). Two further passes fold in the same
    event announced under a decorated title ("Rybnik: Niezły Burdel – komedia
    teatralna" vs "Niezły burdel") — by whole-phrase containment on the same day,
    and by shared distinctive words at the exact same start time."""
    by_key: dict[tuple[str, str], Event] = {}
    dropped = 0
    for ev in sorted(events, key=_richness, reverse=True):
        key = (normalise_title(ev.title), ev.start[:10])
        if key in by_key:
            _merge_venue(by_key[key], ev)
            dropped += 1
        else:
            by_key[key] = ev

    kept: list[Event] = []
    per_day: dict[str, list[Event]] = {}
    for ev in sorted(by_key.values(), key=_richness, reverse=True):
        norm = normalise_title(ev.title)
        same_day = per_day.setdefault(ev.start[:10], [])
        duplicate = None
        for other in same_day:
            a, b = norm, normalise_title(other.title)
            short, long = (a, b) if len(a) <= len(b) else (b, a)
            if len(short) >= 12 and len(short.split()) >= 2 and re.search(
                rf"(?:^|\s){re.escape(short)}(?:\s|$)", long
            ):
                duplicate = other
                break
        if duplicate is not None:
            _merge_venue(duplicate, ev)
            dropped += 1
            continue
        same_day.append(ev)
        kept.append(ev)

    # Third pass: identical start *time* + two or more distinctive words in
    # common. Same-slot collisions between genuinely different events are rare,
    # while the same gig listed by three portals is the norm here.
    final: list[Event] = []
    per_slot: dict[str, list[tuple[Event, set[str]]]] = {}
    for ev in kept:
        tokens = significant_tokens(ev.title)
        slot = per_slot.setdefault(ev.start, [])
        twin = next((other for other, other_tokens in slot if len(tokens & other_tokens) >= 2), None)
        if twin is not None:
            _merge_venue(twin, ev)
            dropped += 1
            continue
        slot.append((ev, tokens))
        final.append(ev)
    return final, dropped


def ensure_unique_ids(events: Iterable[Event]) -> None:
    seen: set[str] = set()
    for ev in events:
        base = ev.id
        n = 2
        while ev.id in seen:
            ev.id = f"{base}-{n}"
            n += 1
        seen.add(ev.id)


# --------------------------------------------------------------------------- #
# manual entries
# --------------------------------------------------------------------------- #

MANUAL_PATH = Path(__file__).parent / "data" / "manual_events.json"


def scrape_manual() -> list[Event]:
    """Hand-maintained events, merged in like any other source.

    Some venues — Klub NOC and Szepty above all — publish only to Facebook, and there
    is no lawful machine-readable feed for them (Meta removed the Page events API, and
    every ticketing platform lists the venues with zero events). Rather than scrape Meta,
    those go in data/manual_events.json by hand. This reader never writes that file.
    """
    if not MANUAL_PATH.exists():
        print("[info] no manual_events.json — skipping", file=sys.stderr)
        return []

    raw = json.loads(MANUAL_PATH.read_text(encoding="utf-8"))
    out: list[Event] = []
    for i, entry in enumerate(raw.get("events", []), 1):
        title = (entry.get("title") or "").strip()
        start = (entry.get("start") or "").strip()
        venue = (entry.get("venue") or "").strip()
        if not (title and start and venue):
            print(f"[warn] manual #{i}: brak title/start/venue — pomijam", file=sys.stderr)
            continue
        try:
            datetime.fromisoformat(start)
        except ValueError:
            print(f"[warn] manual #{i} ({title!r}): zla data {start!r} — pomijam", file=sys.stderr)
            continue

        category = (entry.get("category") or "Inne").strip()
        if category not in VALID_CATEGORIES:
            print(f"[warn] manual #{i} ({title!r}): nieznana kategoria {category!r} "
                  f"— ustawiam Inne", file=sys.stderr)
            category = "Inne"

        out.append(Event(
            id=f"manual-{slugify(title)}-{start[:10]}",
            title=title,
            category=category,
            start=start,
            end=(entry.get("end") or None),
            venue=venue,
            description=(entry.get("description") or None),
            sourceName=venue,
            sourceUrl=(entry.get("sourceUrl") or ""),
        ))

    print(f"[info] manual: {len(out)} wydarzen", file=sys.stderr)
    return out


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

SOURCES: list[tuple[str, Callable[[], list[Event]]]] = [
    ("TZR", scrape_tzr),
    ("iRybnik", scrape_irybnik),
    ("biletyna.pl", scrape_biletyna),
    *[(name, make_dk_scraper(name, url, prefix, venue)) for name, url, prefix, venue in DK_SOURCES],
    ("ROW Rybnik", scrape_row),
    ("Ręcznie dodane", scrape_manual),
]


def main() -> int:
    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "events.json"

    all_events: list[Event] = []
    failures: list[dict] = []
    per_source_raw: dict[str, int] = {}

    for name, scrape in SOURCES:
        print(f"\n[info] === {name} ===", file=sys.stderr)
        try:
            events = scrape()
        except Exception as e:  # noqa: BLE001 — one dead site must not kill the run
            print(f"[error] source {name} failed: {e}", file=sys.stderr)
            failures.append({"source": name, "error": f"{type(e).__name__}: {e}"})
            per_source_raw[name] = 0
            continue
        per_source_raw[name] = len(events)
        all_events.extend(events)

    # Keep future/current only (grace: 3h after start for last-minute checkers)
    cutoff = iso(datetime.now() - timedelta(hours=3))
    future = [e for e in all_events if (e.end or e.start) >= cutoff]
    events, dropped = deduplicate(future)
    events.sort(key=lambda e: (e.start, e.title))
    ensure_unique_ids(events)

    sources = sorted({e.sourceName for e in events})
    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "sources": sources,
        "count": len(events),
        "failures": failures,
        "events": [asdict(e) for e in events],
    }
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    report(per_source_raw, future, events, dropped, failures, out_path)
    return 0


def report(per_source_raw, future, events, dropped, failures, out_path) -> None:
    from collections import Counter

    print("\n" + "=" * 62, file=sys.stderr)
    print("PER-SOURCE COUNTS (scraped → upcoming → kept after dedup)", file=sys.stderr)
    kept_by_source = Counter(e.sourceName for e in events)
    upcoming_by_source = Counter(e.sourceName for e in future)
    for name, raw in per_source_raw.items():
        print(f"  {name:<16} {raw:>4} → {upcoming_by_source.get(name, 0):>4} → "
              f"{kept_by_source.get(name, 0):>4}", file=sys.stderr)
    print(f"  {'TOTAL':<16} {sum(per_source_raw.values()):>4} → {len(future):>4} → "
          f"{len(events):>4}   (deduped away: {dropped})", file=sys.stderr)

    print("\nCATEGORY HISTOGRAM", file=sys.stderr)
    hist = Counter(e.category for e in events)
    for cat, n in hist.most_common():
        print(f"  {cat:<12} {n:>4}  {'#' * n}", file=sys.stderr)

    # --- assertions -------------------------------------------------------- #
    problems: list[str] = []
    ids: set[str] = set()
    for e in events:
        if e.category not in VALID_CATEGORIES:
            problems.append(f"{e.id}: bad category {e.category!r}")
        try:
            datetime.fromisoformat(e.start)
        except ValueError:
            problems.append(f"{e.id}: bad start {e.start!r}")
        if not e.sourceUrl:
            problems.append(f"{e.id}: empty sourceUrl")
        if not e.title:
            problems.append(f"{e.id}: empty title")
        if e.id in ids:
            problems.append(f"duplicate id {e.id}")
        ids.add(e.id)
    if problems:
        print("\n[FAIL] contract violations:", file=sys.stderr)
        for p in problems[:20]:
            print(f"  - {p}", file=sys.stderr)
        raise SystemExit(1)
    print("\n[ok] all events pass the contract checks "
          "(category enum, ISO start, sourceUrl, unique id)", file=sys.stderr)

    if hist.get("Impreza", 0) == 0:
        print("[warn] zero Impreza events — the nightlife goal is not being met!", file=sys.stderr)

    print("\nSAMPLES (one per source)", file=sys.stderr)
    shown: set[str] = set()
    for e in events:
        if e.sourceName in shown:
            continue
        shown.add(e.sourceName)
        print(f"  [{e.sourceName}] {e.start} | {e.category:<9} | {e.title[:52]}\n"
              f"      @ {e.venue[:70]}", file=sys.stderr)
    print(f"\n[info] wrote {len(events)} events (failures: {len(failures)}) → {out_path}",
          file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
