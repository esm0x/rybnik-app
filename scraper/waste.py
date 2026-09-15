#!/usr/bin/env python3
"""Scrape the 2026 waste-collection schedules from rybnik.eu → scraper/data/waste.json

Approach:
  1. Scrape the index page for PDF links, keep only
       zamieszkale2026_<Dzielnice>_2026.pdf  (single-family, 15 files)
       Wielorodzinna_2026.pdf                (multi-family, 1 file)
     Skip Firmy2026_* (businesses) and the ad-hoc "utrudniony dojazd" leaflets.
  2. Single-family PDF: page 1 holds a `Rejony | Nazwy ulic` table, page 2+ holds
     the schedule as repeating blocks (header row + ZMIESZANE / POPIOŁY / SEGREGOWANE
     / BIODEGRADOWALNE rows). Join the two by rejon name.
  3. Multi-family PDF: one wide table, weekday rules instead of dates.

Why this file does NOT use page.extract_text()/extract_words():
  These PDFs carry a text matrix with `-0.0` in the off-diagonal slots, so pdfplumber
  flags every char as `upright=False`. Its word grouper then sorts/merges chars as if
  the page were rotated and returns letter-soup. We therefore work straight off
  `page.chars`: group into visual rows by `top`, cluster chars into tokens by x-gap,
  and bind every token to a column using the header row's x positions. Column binding
  is mandatory — a rendered row reads `ZMIESZANE15121210;237;21...` and `15121210`
  cannot be split by string alone.

Field names and enum values emitted here MUST match scraper/CONTRACT.md 1:1.
"""

from __future__ import annotations

import collections
import io
import json
import re
import sys
import unicodedata
from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Iterable, Optional
from urllib.parse import urljoin

import pdfplumber
import requests
from bs4 import BeautifulSoup

BASE = "https://www.rybnik.eu"
INDEX_URL = f"{BASE}/dla-mieszkancow/odpady-komunalne/harmonogramy-odbioru-2026"
YEAR = 2026
UA = "Mozilla/5.0 (compatible; RybnikAppBot/0.1; +https://github.com/YOUR_GH_USER/rybnik-app)"

SINGLE_RX = re.compile(r"^zamieszkale2026_.+_2026\.pdf$", re.IGNORECASE)
MULTI_NAME = "wielorodzinna_2026.pdf"

# --- layout tuning -----------------------------------------------------------
ROW_TOL = 2.5   # chars within this many pt of each other vertically are one visual row
CHAR_GAP = 4.0  # x-gap (pt) below which two chars belong to the same token.
                # Smallest observed gap between two different columns is ~18pt,
                # and justified prose inside one cell has ~2pt inter-word gaps.
BAND_GAP = 10.0  # vertical gap (pt) that separates two rejony in the street table

MONTHS_PL = [
    "styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec",
    "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień",
]
MONTH_INDEX = {m: i + 1 for i, m in enumerate(MONTHS_PL)}

# PDF waste label (normalised: uppercase, no spaces) → contract enum name
WASTE_TYPES = {
    "ZMIESZANE": "ZMIESZANE",
    "POPIOŁY/ŻUŻEL": "POPIOLY",
    "POPIOLY/ZUZEL": "POPIOLY",
    "SEGREGOWANE": "SEGREGOWANE",
    "BIODEGRADOWALNE": "BIO",
    "PLASTIK": "PLASTIK",
    "PAPIER": "PAPIER",
    "SZKŁO": "SZKLO",
    "SZKLO": "SZKLO",
    "WIELKOGABARYTY": "GABARYTY",
    "BIO": "BIO",
}
GABARYTY = "GABARYTY"
TYPE_ORDER = ["ZMIESZANE", "POPIOLY", "SEGREGOWANE", "BIO", "GABARYTY",
              "PLASTIK", "PAPIER", "SZKLO"]

WEEKDAYS_PL = {
    "poniedziałek": "MONDAY",
    "wtorek": "TUESDAY",
    "środa": "WEDNESDAY",
    "czwartek": "THURSDAY",
    "piątek": "FRIDAY",
    "sobota": "SATURDAY",
    "niedziela": "SUNDAY",
}
WEEKDAY_RX = re.compile("|".join(WEEKDAYS_PL), re.IGNORECASE)

# Rows that mark the end of the schedule table on a single-family page.
FOOTER_RX = re.compile(
    r"^\s*\*|AKCJA CHOINKA|EKO Sp\.|Internet:|Facebook:|Kościuszki 45a", re.IGNORECASE
)

PL_MAP = str.maketrans({
    "ą": "a", "ć": "c", "ę": "e", "ł": "l", "ń": "n", "ó": "o",
    "ś": "s", "ź": "z", "ż": "z",
    "Ą": "A", "Ć": "C", "Ę": "E", "Ł": "L", "Ń": "N", "Ó": "O",
    "Ś": "S", "Ź": "Z", "Ż": "Z",
})


# ---------------------------------------------------------------------------
# data model (mirrors CONTRACT.md § waste.json)
# ---------------------------------------------------------------------------

@dataclass
class Rule:
    from_: Optional[int]
    to: Optional[int]
    parity: Optional[str]  # "ODD" | "EVEN" | None

    def to_json(self) -> dict:
        return {"from": self.from_, "to": self.to, "parity": self.parity}


@dataclass
class Street:
    name: str
    rules: list[Rule] = field(default_factory=list)

    def to_json(self) -> dict:
        return {"name": self.name, "rules": [r.to_json() for r in self.rules]}


@dataclass
class Pickup:
    type: str
    dates: list[str]


@dataclass
class WeekdayRule:
    type: str
    weekdays: list[str]
    weekParity: Optional[str]


@dataclass
class Rejon:
    id: str
    name: str
    district: str
    houseType: str
    streets: list[Street] = field(default_factory=list)
    pickups: list[Pickup] = field(default_factory=list)
    weekdayRules: list[WeekdayRule] = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "district": self.district,
            "houseType": self.houseType,
            "streets": [s.to_json() for s in self.streets],
            "pickups": [asdict(p) for p in self.pickups],
            "weekdayRules": [asdict(w) for w in self.weekdayRules],
        }


# ---------------------------------------------------------------------------
# generic helpers
# ---------------------------------------------------------------------------

def log(msg: str) -> None:
    print(msg, file=sys.stderr)


def http_get(url: str) -> bytes:
    r = requests.get(url, headers={"User-Agent": UA}, timeout=60)
    r.raise_for_status()
    return r.content


def slugify(text: str) -> str:
    s = text.translate(PL_MAP)
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode("ascii")
    s = re.sub(r"[^A-Za-z0-9]+", "-", s).strip("-").lower()
    return s or "rejon"


def squash(text: str) -> str:
    """Collapse whitespace; PDFs are full of stray double spaces and NBSPs."""
    return re.sub(r"\s+", " ", text.replace("\xa0", " ")).strip()


# ---------------------------------------------------------------------------
# char-level page model
# ---------------------------------------------------------------------------

@dataclass
class Token:
    text: str
    x0: float
    x1: float

    @property
    def center(self) -> float:
        return (self.x0 + self.x1) / 2


@dataclass
class Row:
    top: float
    tokens: list[Token]

    @property
    def text(self) -> str:
        return squash(" ".join(t.text for t in self.tokens))


def page_rows(page) -> list[Row]:
    """Group page.chars into visual rows, then into x-gap-delimited tokens."""
    chars = sorted(page.chars, key=lambda c: (round(c["top"], 1), c["x0"]))
    buckets: list[tuple[float, list[dict]]] = []
    for ch in chars:
        if buckets and abs(ch["top"] - buckets[-1][0]) <= ROW_TOL:
            buckets[-1][1].append(ch)
        else:
            buckets.append((ch["top"], [ch]))

    rows: list[Row] = []
    for top, group in buckets:
        group.sort(key=lambda c: c["x0"])
        tokens: list[Token] = []
        for ch in group:
            if tokens and ch["x0"] - tokens[-1].x1 < CHAR_GAP:
                tokens[-1].text += ch["text"]
                tokens[-1].x1 = max(tokens[-1].x1, ch["x1"])
            else:
                tokens.append(Token(ch["text"], ch["x0"], ch["x1"]))
        tokens = [Token(squash(t.text), t.x0, t.x1) for t in tokens]
        tokens = [t for t in tokens if t.text]
        if tokens:
            rows.append(Row(top, tokens))
    return rows


class Columns:
    """Maps an x position to a named column, using the header row's token centers.

    Boundaries are the midpoints between neighbouring header centers. Binding is done
    per *token*, not per char: long cell contents (street lists) are rendered wider
    than their cell and would otherwise bleed into the neighbouring column.
    """

    def __init__(self, names: list[str], centers: list[float]) -> None:
        pairs = sorted(zip(centers, names))
        self.centers = [c for c, _ in pairs]
        self.names = [n for _, n in pairs]
        self.bounds = [
            (self.centers[i] + self.centers[i + 1]) / 2 for i in range(len(pairs) - 1)
        ]

    def column_of(self, x: float) -> str:
        for i, b in enumerate(self.bounds):
            if x < b:
                return self.names[i]
        return self.names[-1]

    def bind(self, rows: Iterable[Row]) -> dict[str, str]:
        """Merge several visual rows of one table row into {column: text}."""
        cells: dict[str, list[str]] = {n: [] for n in self.names}
        for row in rows:
            for tok in row.tokens:
                cells[self.column_of(tok.center)].append(tok.text)
        return {k: squash(" ".join(v)) for k, v in cells.items()}


def horizontal_bands(page) -> list[tuple[float, float]]:
    """Row bands derived from the table's horizontal ruling lines."""
    tops = sorted({round(e["top"], 1) for e in page.edges if e["orientation"] == "h"})
    merged: list[float] = []
    for t in tops:
        if not merged or t - merged[-1] > 1.5:
            merged.append(t)
    return [(merged[i], merged[i + 1]) for i in range(len(merged) - 1)]


# ---------------------------------------------------------------------------
# street-range grammar
# ---------------------------------------------------------------------------
#   entry   := name [ clause ("i" clause)* ]
#   clause  := ["od" ["nr"] NUM] ["do" ["nr"] NUM] [parity]
#   parity  := "niep." (odd) | "parz." (even)
# e.g. "Św.Józefa od 17 do 73 niep. i od 22 do 82 parz."
#      "Zebrzydowica do 128B parz. i do 125 niep."   (letter suffix dropped → 128)

NUM = r"(\d+)\s*[A-Za-zĄ-ż]?\.?"          # "38", "128B", "106." → the int only
CONSTRAINT_START_RX = re.compile(r"(?<![^\W\d_])(od|do)(?![^\W\d_])", re.IGNORECASE)
CONJUNCTION_RX = re.compile(r"(?<![^\W\d_])i(?![^\W\d_])", re.IGNORECASE)
PARITY = r"(niep\w*\.?|np\.|parz\w*\.?)"  # "niep.", "nieparzyste", "np." (typo), "parz."
CLAUSE_RX = re.compile(
    rf"^\s*(?:(od)\s+(?:nr\s*)?{NUM})?"
    rf"\s*(?:(do)\s+(?:(?:nr\s*){NUM}|ko[nń]ca|{NUM}))?"
    rf"\s*{PARITY}?\s*[.,]?\s*$",
    re.IGNORECASE,
)
# "… od 49 niep. i 44 parz." — the second clause drops the keyword and inherits it
BARE_CLAUSE_RX = re.compile(rf"^\s*{NUM}\s*{PARITY}?\s*[.,]?\s*$", re.IGNORECASE)
# "Wyzwolenia 79A" / "Zebrzydowicka 140-142" — a bare house number or number range
HOUSE_SUFFIX_RX = re.compile(rf"\s{NUM}\s*(?:-\s*{NUM})?$")
PAREN_RX = re.compile(r"\s*\(([^()]*)\)\s*$")
NUMBER_LIST_RX = re.compile(r"^\d+[A-Za-zĄ-ż]?(\s*,\s*\d+[A-Za-zĄ-ż]?)*$")
# A lone initial produced by a typo'd separator, e.g. "A,Stefek" → ["A", "Stefek"]
INITIAL_RX = re.compile(r"^[A-ZĄĆĘŁŃÓŚŹŻ]\.?$")


def parity_of(token: Optional[str]) -> Optional[str]:
    if not token:
        return None
    return "EVEN" if token.lower().startswith("parz") else "ODD"


def parse_clause(text: str, inherited: Optional[str]) -> tuple[Optional[Rule], Optional[str]]:
    """One `[od N] [do M] [parity]` clause.

    Returns (rule, keyword) — the keyword is carried into the next clause so that a
    bare "i 44 parz." keeps the "od" of the clause before it. A None rule means the
    clause was not understood."""
    m = CLAUSE_RX.match(text)
    if m:
        kw_od, lo, kw_do, hi_nr, hi, par = m.groups()
        hi = hi_nr or hi
        if lo is None and hi is None and par is None:
            return None, inherited
        rule = Rule(int(lo) if lo else None, int(hi) if hi else None, parity_of(par))
        keyword = "do" if (kw_do and not kw_od) else ("od" if kw_od else inherited)
        return rule, keyword

    bare = BARE_CLAUSE_RX.match(text)
    if bare and inherited:
        num, par = bare.groups()
        lo = int(num) if inherited == "od" else None
        hi = int(num) if inherited == "do" else None
        return Rule(lo, hi, parity_of(par)), inherited
    return None, inherited


def parse_street_entry(entry: str, notes: list[str]) -> Optional[Street]:
    entry = squash(entry).strip(" ,;")
    if not entry:
        return None

    extra: list[Rule] = []
    paren = PAREN_RX.search(entry)
    if paren:
        inner, head = paren.group(1).strip(), entry[: paren.start()].strip()
        if re.match(r"poza\b", inner, re.IGNORECASE):
            # "(poza numerami 15g,17e)" — an exclusion list; the contract cannot
            # express it, so the range stays slightly too wide. Flag it.
            notes.append(f"dropped exclusion on {head!r}: ({inner})")
            entry = head
        elif NUMBER_LIST_RX.match(inner):
            # "(15g,15h,17e)" — an explicit list of house numbers
            extra = [Rule(int(n), int(n), None) for n in re.findall(r"\d+", inner)]
            entry = head
        else:
            notes.append(f"unparsed note on {entry!r}")
            return Street(entry, [])

    m = CONSTRAINT_START_RX.search(entry)
    if not m:
        house = HOUSE_SUFFIX_RX.search(entry)
        if house and not entry[: house.start()].strip(" .").isdigit():
            lo, hi = house.group(1), house.group(2)
            extra.append(Rule(int(lo), int(hi or lo), None))
            entry = entry[: house.start()].strip()
        return Street(entry, dedupe_rules(extra))

    name = squash(entry[: m.start()]).strip(" ,;")
    if not name:  # constraint keyword with no street in front of it — keep raw
        return Street(entry, [])
    rules: list[Rule] = []
    keyword: Optional[str] = None
    for part in CONJUNCTION_RX.split(entry[m.start():]):
        rule, keyword = parse_clause(part, keyword)
        if rule is None:
            # One clause we do not understand means the whole range is unknown.
            # Emitting a partial range would silently hand the app wrong addresses,
            # so keep the raw label instead — it simply never matches.
            notes.append(f"unparsed range: {entry!r}")
            return Street(entry, [])
        rules.append(rule)
    return Street(name, dedupe_rules(rules + extra))


def dedupe_rules(rules: list[Rule]) -> list[Rule]:
    out: list[Rule] = []
    seen: set[tuple] = set()
    for r in rules:
        key = (r.from_, r.to, r.parity)
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def split_entries(cell: str) -> list[str]:
    """Split the comma-separated street list, ignoring commas inside parentheses."""
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    for ch in squash(cell):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    parts.append("".join(buf))

    merged: list[str] = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        if merged and INITIAL_RX.match(merged[-1]):
            # "A,Stefek" — a comma typed instead of a period inside an initial
            merged[-1] = merged[-1].rstrip(".") + "." + p
        else:
            merged.append(p)
    return merged


def parse_street_list(cell: str, notes: list[str]) -> list[Street]:
    streets: list[Street] = []
    seen: set[tuple] = set()
    for part in split_entries(cell):
        st = parse_street_entry(part, notes)
        if st is None:
            continue
        key = (st.name, tuple((r.from_, r.to, r.parity) for r in st.rules))
        if key in seen:
            continue
        seen.add(key)
        streets.append(st)
    return streets


# ---------------------------------------------------------------------------
# single-family parsing
# ---------------------------------------------------------------------------

def normalise_rejon_name(text: str) -> str:
    s = squash(text)
    s = re.sub(r"\s*-\s*", "-", s)          # "Maroko - Nowiny" → "Maroko-Nowiny"
    s = re.sub(r"\s*\.\s*", ".", s)
    return s


# The single-family and multi-family PDFs disagree on word order for this one
# district, which would otherwise show up twice in the app's address picker.
# "Rybnicka Kuznia" is the official name.
DISTRICT_ALIASES = {
    "Kuźnia Rybnicka": "Rybnicka Kuźnia",
}


def district_of(name: str) -> str:
    base = squash(re.sub(r"\s*\d+\s*$", "", name)) or name
    return DISTRICT_ALIASES.get(base, base)


def parse_day_cell(text: str) -> list[int]:
    t = squash(text)
    if not t or t in {"-", "–", "—"}:
        return []
    days: list[int] = []
    for part in re.split(r"[;,]", t):
        part = part.strip().strip(".")
        if not part or part in {"-", "–", "—"}:
            continue
        if not re.fullmatch(r"\d{1,2}", part):
            raise ValueError(f"unparseable day cell {text!r}")
        days.append(int(part))
    return days


def match_key(text: str) -> str:
    """Comparison key for rejon names: accent-free, lowercase, punctuation-free."""
    return re.sub(r"[^a-z0-9]", "", slugify(text))


def group_labels(labels: list[tuple[int, str]], expected: list[str]) -> Optional[list[list[int]]]:
    """Fold the rejon-column fragments into one group per expected rejon.

    A label wraps across rows ("Boguszowice Stare" / "1"), and the rows in between may
    belong to the street column, so fragments are folded by name, not by geometry.
    """
    groups: list[list[int]] = []
    i = 0
    for name in expected:
        if i >= len(labels):
            return None
        want = match_key(name)
        idxs = [labels[i][0]]
        got = labels[i][1]
        i += 1
        while i < len(labels) and want.startswith(match_key(got)) and match_key(got) != want:
            idxs.append(labels[i][0])
            got = f"{got} {labels[i][1]}"
            i += 1
        groups.append(idxs)
    return groups if i == len(labels) else None


def split_bands(rows: list[Row], groups: list[list[int]]) -> list[list[Row]]:
    """Cut the row list into one band per label group.

    The rejon label is vertically centred in its band, so for each band we pick the
    cut that leaves the label group sitting in the middle of the rows it owns.
    """
    bands: list[list[Row]] = []
    start = 0
    for gi, idxs in enumerate(groups):
        if gi == len(groups) - 1:
            bands.append(rows[start:])
            break
        label_center = (idxs[0] + idxs[-1]) / 2
        lo, hi = idxs[-1], groups[gi + 1][0] - 1
        best = min(
            range(lo, hi + 1),
            key=lambda k: (abs(label_center - (start + k) / 2), k),
        )
        bands.append(rows[start:best + 1])
        start = best + 1
    return bands


def parse_street_table(
    page, expected: list[str], failures: list[dict], source: str
) -> list[tuple[str, list[Street]]]:
    """Page 1: `Rejony | Nazwy ulic`, in the same rejon order as the schedule page."""
    rows = page_rows(page)
    header = next(
        (r for r in rows if "Rejony" in r.text and "Nazwy ulic" in r.text), None
    )
    if header is None:
        raise ValueError("no 'Rejony | Nazwy ulic' header on page 1")
    cols = Columns(
        ["rejon", "ulice"],
        [
            next(t.center for t in header.tokens if "Rejony" in t.text),
            next(t.center for t in header.tokens if "Nazwy" in t.text),
        ],
    )

    body: list[Row] = []
    for row in rows[rows.index(header) + 1:]:
        if FOOTER_RX.search(row.text):
            break
        body.append(row)

    labels = [
        (i, normalise_rejon_name(cols.bind([r])["rejon"]))
        for i, r in enumerate(body)
        if cols.bind([r])["rejon"]
    ]
    groups = group_labels(labels, expected) if expected else None
    if groups is None:
        # Fallback: only about half of these PDFs draw table borders, so group by
        # vertical spacing (inside a rejon rows sit ~7pt apart, between rejony ~14pt+).
        log(f"[warn] {source}: rejon labels do not line up with the schedule, "
            f"falling back to spacing-based bands")
        failures.append({"source": source, "error": "street table / schedule name mismatch"})
        bands: list[list[Row]] = []
        for row in body:
            if bands and row.top - bands[-1][-1].top <= BAND_GAP:
                bands[-1].append(row)
            else:
                bands.append([row])
    else:
        bands = split_bands(body, groups)

    out: list[tuple[str, list[Street]]] = []
    for band_rows in bands:
        cells = cols.bind(band_rows)
        name = normalise_rejon_name(cells["rejon"])
        if not name or name.lower().startswith("rejon"):
            continue
        notes: list[str] = []
        try:
            streets = parse_street_list(cells["ulice"], notes)
        except Exception as exc:  # noqa: BLE001
            failures.append({"source": source, "error": f"streets for {name}: {exc}"})
            streets = []
        for note in notes:
            log(f"[warn] {source}: {name}: {note}")
        if not streets:
            log(f"[warn] {source}: rejon {name!r} has no streets")
        out.append((name, streets))
    return out


def schedule_columns(header: Row) -> Optional[Columns]:
    names: list[str] = []
    centers: list[float] = []
    for tok in header.tokens:
        low = tok.text.lower()
        if low.startswith("rejon"):
            names.append("rejon")
        elif low.startswith("typ"):
            names.append("typ")
        elif low.startswith("g+e"):
            names.append("ge")
        elif low in MONTH_INDEX:
            names.append(low)
        else:
            continue
        centers.append(tok.center)
    if "rejon" not in names or "typ" not in names:
        return None
    if sum(1 for n in names if n in MONTH_INDEX) != 12:
        return None
    return Columns(names, centers)


def parse_schedule(pdf, failures: list[dict], source: str) -> dict[str, list[Pickup]]:
    """Pages 2+: repeating 5-row blocks, each opened by its own header row."""
    result: dict[str, dict[str, set[str]]] = {}

    for page in pdf.pages[1:]:
        rows = page_rows(page)
        blocks: list[tuple[Columns, list[Row]]] = []
        current: Optional[tuple[Columns, list[Row]]] = None
        for row in rows:
            cols = schedule_columns(row)
            if cols is not None:
                current = (cols, [])
                blocks.append(current)
                continue
            if current is None:
                continue
            if FOOTER_RX.search(row.text):
                current = None
                continue
            current[1].append(row)

        for cols, block_rows in blocks:
            if not block_rows:
                continue
            try:
                name, pickups = parse_block(cols, block_rows)
            except Exception as exc:  # noqa: BLE001 — one bad block must not kill the file
                log(f"[warn] {source}: block skipped: {exc}")
                failures.append({"source": source, "error": f"block: {exc}"})
                continue
            if not name:
                failures.append({"source": source, "error": "block without rejon name"})
                continue
            bucket = result.setdefault(name, {})
            for wtype, dates in pickups.items():
                bucket.setdefault(wtype, set()).update(dates)

    return {
        name: [
            Pickup(t, sorted(buckets[t]))
            for t in TYPE_ORDER
            if t in buckets and buckets[t]
        ]
        for name, buckets in result.items()
    }


def parse_block(cols: Columns, rows: list[Row]) -> tuple[str, dict[str, set[str]]]:
    name_parts: list[str] = []
    pickups: dict[str, set[str]] = {}

    for row in rows:
        cells = cols.bind(rows=[row])
        if cells["rejon"]:
            name_parts.append(cells["rejon"])

        ge = cells.get("ge", "")
        for m in re.finditer(r"(\d{1,2})\.(\d{1,2})\.(\d{4})", ge):
            d, mo, y = (int(g) for g in m.groups())
            pickups.setdefault(GABARYTY, set()).add(date(y, mo, d).isoformat())

        label = re.sub(r"\s+", "", cells["typ"]).upper()
        if not label:
            continue
        wtype = WASTE_TYPES.get(label)
        if wtype is None:
            raise ValueError(f"unknown waste type {cells['typ']!r}")
        for month, idx in MONTH_INDEX.items():
            for day in parse_day_cell(cells.get(month, "")):
                pickups.setdefault(wtype, set()).add(date(YEAR, idx, day).isoformat())

    return normalise_rejon_name(" ".join(name_parts)), pickups


def merge_names(schedule_name: str, street_name: str) -> str:
    """The two tables occasionally abbreviate differently ("Kuźnia" vs "Kuźnia
    Rybnicka"); keep the more specific spelling when one is a prefix of the other."""
    a, b = match_key(schedule_name), match_key(street_name)
    if b.startswith(a) and len(b) > len(a):
        return street_name
    return schedule_name


def parse_single_family(data: bytes, source: str, failures: list[dict]) -> list[Rejon]:
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        pickups_by_rejon = parse_schedule(pdf, failures, source)
        schedule_names = list(pickups_by_rejon)
        street_bands = parse_street_table(pdf.pages[0], schedule_names, failures, source)

    if len(street_bands) != len(schedule_names):
        log(f"[warn] {source}: {len(schedule_names)} rejony in the schedule but "
            f"{len(street_bands)} in the street table")
        failures.append({
            "source": source,
            "error": f"rejon count mismatch: schedule={len(schedule_names)}, "
                     f"streets={len(street_bands)}",
        })

    by_streets = {match_key(n): (n, s) for n, s in street_bands}
    rejony: list[Rejon] = []
    for i, sched_name in enumerate(schedule_names):
        if i < len(street_bands) and len(street_bands) == len(schedule_names):
            street_name, streets = street_bands[i]
        else:  # counts disagree — fall back to matching by name
            street_name, streets = by_streets.get(match_key(sched_name), (sched_name, []))
        name = merge_names(sched_name, street_name)
        if match_key(sched_name) != match_key(street_name):
            log(f"[warn] {source}: schedule says {sched_name!r}, "
                f"street table says {street_name!r} → using {name!r}")
        if not streets:
            failures.append({"source": source, "error": f"{name}: no streets"})
        rejony.append(
            Rejon(
                id=slugify(name),
                name=name,
                district=district_of(name),
                houseType="SINGLE_FAMILY",
                streets=streets,
                pickups=pickups_by_rejon[sched_name],
                weekdayRules=[],
            )
        )
    return rejony


# ---------------------------------------------------------------------------
# multi-family parsing
# ---------------------------------------------------------------------------

def parse_weekday_cell(text: str) -> Optional[tuple[list[str], Optional[str]]]:
    t = squash(text).lower()
    if not t or t in {"-", "–", "—"}:
        return None
    parity = None
    if re.search(r"tydzie[nń]\s+nieparz", t):
        parity = "ODD"
    elif re.search(r"tydzie[nń]\s+parz", t):
        parity = "EVEN"
    t = re.sub(r"tydzie[nń]\s+(nie)?parzysty?", " ", t)
    days: list[str] = []
    for m in WEEKDAY_RX.finditer(t):
        day = WEEKDAYS_PL[m.group(0).lower()]
        if day not in days:
            days.append(day)
    if not days:
        return None
    return days, parity


def multi_columns(rows: list[Row]) -> tuple[Columns, list[Row]]:
    top_row = next((r for r in rows if "DZIELNICA" in r.text.upper()), None)
    type_row = next(
        (r for r in rows if "ZMIESZANE" in r.text.upper() and "PLASTIK" in r.text.upper()),
        None,
    )
    if top_row is None or type_row is None:
        raise ValueError("multi-family header row not found")
    names: list[str] = []
    centers: list[float] = []
    for tok in list(top_row.tokens) + list(type_row.tokens):
        key = re.sub(r"\s+", "", tok.text).upper()
        if key in {"DZIELNICA", "ULICE"} or key in WASTE_TYPES:
            names.append(key)
            centers.append(tok.center)
    if len(names) != 8:
        raise ValueError(f"multi-family header has {len(names)} columns, expected 8")
    return Columns(names, centers), [top_row, type_row]


def parse_multi_family(data: bytes, source: str, failures: list[dict]) -> list[Rejon]:
    rejony: list[Rejon] = []
    seen_ids: dict[str, int] = {}

    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for page in pdf.pages:
            rows = page_rows(page)
            try:
                cols, header_rows = multi_columns(rows)
            except ValueError as exc:
                failures.append({"source": source, "error": str(exc)})
                continue
            header_bottom = max(r.top for r in header_rows)

            for lo, hi in horizontal_bands(page):
                if hi <= header_bottom:
                    continue
                band_rows = [
                    r for r in rows
                    if lo - 0.5 <= r.top < hi - 0.5
                    and r not in header_rows
                    and r.top > header_bottom
                ]
                if not band_rows:
                    continue
                cells = cols.bind(band_rows)
                name = normalise_rejon_name(cells["DZIELNICA"])
                if not name or name.upper() == "DZIELNICA":
                    continue
                notes: list[str] = []
                try:
                    streets = parse_street_list(cells["ULICE"], notes)
                    rules: list[WeekdayRule] = []
                    for key in ("ZMIESZANE", "PLASTIK", "PAPIER", "SZKŁO",
                                "WIELKOGABARYTY", "BIO"):
                        parsed = parse_weekday_cell(cells.get(key, ""))
                        if parsed is None:
                            continue
                        days, parity = parsed
                        rules.append(WeekdayRule(WASTE_TYPES[key], days, parity))
                except Exception as exc:  # noqa: BLE001
                    log(f"[warn] {source}: {name}: {exc}")
                    failures.append({"source": source, "error": f"{name}: {exc}"})
                    continue
                for note in notes:
                    log(f"[warn] {source}: {name}: {note}")

                if not streets:
                    log(f"[warn] {source}: rejon {name!r} has no streets")
                if not rules:
                    log(f"[warn] {source}: rejon {name!r} has no weekday rules")

                base = f"{slugify(name)}-multi"
                seen_ids[base] = seen_ids.get(base, 0) + 1
                rid = base if seen_ids[base] == 1 else f"{base}-{seen_ids[base]}"
                rejony.append(
                    Rejon(
                        id=rid,
                        name=name,
                        district=district_of(name),
                        houseType="MULTI_FAMILY",
                        streets=streets,
                        pickups=[],
                        weekdayRules=rules,
                    )
                )
    return rejony


# ---------------------------------------------------------------------------
# index page
# ---------------------------------------------------------------------------

def collect_pdf_urls(html: str) -> tuple[list[str], Optional[str]]:
    soup = BeautifulSoup(html, "html.parser")
    single: list[str] = []
    multi: Optional[str] = None
    seen: set[str] = set()
    for a in soup.select("a[href]"):
        href = a["href"]
        filename = href.split("/")[-1].split("?")[0]
        full = urljoin(BASE, href)
        if full in seen:
            continue
        if SINGLE_RX.match(filename):
            seen.add(full)
            single.append(full)
        elif filename.lower() == MULTI_NAME:
            seen.add(full)
            multi = full
    return single, multi


# ---------------------------------------------------------------------------
# verification
# ---------------------------------------------------------------------------

def canonicalise_districts(rejony: list[Rejon]) -> None:
    """The two sources punctuate some districts differently ("Boguszowice Osiedle" vs
    "Boguszowice-Osiedle"). Pick one spelling per district so the app groups them."""
    variants: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    for r in rejony:
        variants[match_key(r.district)][r.district] += 1
    canonical = {
        key: min(counts.items(), key=lambda kv: (-kv[1], kv[0]))[0]
        for key, counts in variants.items()
    }
    for r in rejony:
        r.district = canonical[match_key(r.district)]


def verify(rejony: list[Rejon], failures: list[dict]) -> None:
    problems: list[str] = []

    ids = [r.id for r in rejony]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        problems.append(f"duplicate ids: {sorted(dupes)}")

    total_streets = 0
    total_dates = 0
    for r in rejony:
        total_streets += len(r.streets)
        total_dates += sum(len(p.dates) for p in r.pickups)
        if not r.streets:
            problems.append(f"{r.id}: no streets")
        if not r.pickups and not r.weekdayRules:
            problems.append(f"{r.id}: neither pickups nor weekdayRules")
        if r.pickups and r.weekdayRules:
            problems.append(f"{r.id}: has both pickups and weekdayRules")
        if r.houseType == "SINGLE_FAMILY" and r.weekdayRules:
            problems.append(f"{r.id}: single-family with weekdayRules")
        if r.houseType == "MULTI_FAMILY" and r.pickups:
            problems.append(f"{r.id}: multi-family with pickups")
        for p in r.pickups:
            if p.type not in TYPE_ORDER:
                problems.append(f"{r.id}: bad type {p.type}")
            if p.dates != sorted(p.dates):
                problems.append(f"{r.id}/{p.type}: dates not sorted")
            for d in p.dates:
                if not d.startswith(f"{YEAR}-"):
                    problems.append(f"{r.id}/{p.type}: date outside {YEAR}: {d}")
        for w in r.weekdayRules:
            if w.weekParity not in (None, "ODD", "EVEN"):
                problems.append(f"{r.id}: bad weekParity {w.weekParity}")
        for s in r.streets:
            for rule in s.rules:
                if rule.parity not in (None, "ODD", "EVEN"):
                    problems.append(f"{r.id}/{s.name}: bad parity {rule.parity}")

    single = [r for r in rejony if r.houseType == "SINGLE_FAMILY"]
    multi = [r for r in rejony if r.houseType == "MULTI_FAMILY"]
    log("[info] ---- summary ----")
    log(f"[info] rejony: {len(rejony)} "
        f"(SINGLE_FAMILY={len(single)}, MULTI_FAMILY={len(multi)})")
    log(f"[info] streets: {total_streets}, pickup dates: {total_dates}, "
        f"weekday rules: {sum(len(r.weekdayRules) for r in multi)}")
    log(f"[info] parse failures: {len(failures)}")
    for f in failures:
        log(f"[warn]   {f['source']}: {f['error']}")
    if not 70 <= len(single) <= 130:
        problems.append(f"single-family rejon count {len(single)} outside sane range")
    if problems:
        log(f"[warn] {len(problems)} verification problem(s):")
        for p in problems:
            log(f"[warn]   {p}")
    else:
        log("[info] all verification checks passed")


# ---------------------------------------------------------------------------

def main() -> int:
    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "waste.json"

    failures: list[dict] = []
    rejony: list[Rejon] = []

    log(f"[info] fetching index: {INDEX_URL}")
    single_urls, multi_url = collect_pdf_urls(http_get(INDEX_URL).decode("utf-8", "replace"))
    log(f"[info] found {len(single_urls)} single-family PDFs, "
        f"multi-family: {'yes' if multi_url else 'NO'}")
    if len(single_urls) != 15:
        log(f"[warn] expected 15 single-family PDFs, got {len(single_urls)}")

    for i, url in enumerate(single_urls, 1):
        source = url.split("/")[-1]
        try:
            log(f"[info] ({i}/{len(single_urls)}) {source}")
            got = parse_single_family(http_get(url), source, failures)
            log(f"[info]   → {len(got)} rejony")
            rejony.extend(got)
        except Exception as exc:  # noqa: BLE001 — partial data beats no data
            log(f"[error] {source}: {exc}")
            failures.append({"source": source, "error": str(exc)})

    if multi_url:
        source = multi_url.split("/")[-1]
        try:
            log(f"[info] {source}")
            got = parse_multi_family(http_get(multi_url), source, failures)
            log(f"[info]   → {len(got)} rejony")
            rejony.extend(got)
        except Exception as exc:  # noqa: BLE001
            log(f"[error] {source}: {exc}")
            failures.append({"source": source, "error": str(exc)})
    else:
        failures.append({"source": INDEX_URL, "error": "Wielorodzinna_2026.pdf not linked"})

    rejony.sort(key=lambda r: (r.houseType, r.name))
    canonicalise_districts(rejony)
    verify(rejony, failures)

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "year": YEAR,
        "count": len(rejony),
        "failures": failures,
        "rejony": [r.to_json() for r in rejony],
    }
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    log(f"[info] wrote {len(rejony)} rejony (failures: {len(failures)}) → {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
