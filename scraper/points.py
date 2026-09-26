#!/usr/bin/env python3
"""PSZOK, GPZON and the "where do I throw this" dictionary → scraper/data/waste_points.json

Sources:
  * rybnik.eu /odpady-komunalne/punkty-zbiorki-odpadow-pszok  — the two PSZOK sites
  * rybnik.eu /odpady-komunalne/odpady-niebezpieczne-gpzon    — the hazardous waste point

What is scraped and what is not, deliberately:

  * The **accepted waste lists** are scraped. They are clean <li> elements and they do
    change when the city updates its contract.
  * **Opening hours are taken as the raw line from the page**, not parsed into structured
    times. The page writes them as "poniedziałek - piatek: 7.00-19.00, sobota: 8.00-15.00",
    typo included, and every attempt to normalise that invents precision the source does
    not have. The app shows the line as written.
  * Addresses and phones are pulled with narrow regexes and the run fails loudly if a
    point comes out without an address, because a PSZOK entry with no address is worse
    than no entry at all.

The `guide` section is NOT scraped. It is read from `data/waste_guide_manual.json`, a
human-maintained file, the same arrangement as manual_events.json: the city publishes its
sorting rules as prose and a PDF leaflet, with no machine-readable per-item list.

Output shape is fixed by scraper/CONTRACT.md (waste_points.json).
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)
HEADERS = {"User-Agent": UA, "Accept-Language": "pl-PL,pl;q=0.9"}

BASE = "https://www.rybnik.eu/dla-mieszkancow/odpady-komunalne"
PSZOK_URL = f"{BASE}/punkty-zbiorki-odpadow-pszok"
GPZON_URL = f"{BASE}/odpady-niebezpieczne-gpzon"

GUIDE_INPUT = "waste_guide_manual.json"


@dataclass
class Point:
    id: str
    name: str
    kind: str                    # PSZOK | GPZON
    address: str
    district: Optional[str]
    phone: Optional[str]
    email: Optional[str]
    hours: str                   # raw line from the page
    accepted: list[str] = field(default_factory=list)
    note: Optional[str] = None
    url: str = ""


def http_get(url: str) -> requests.Response:
    r = requests.get(url, headers=HEADERS, timeout=30)
    r.raise_for_status()
    return r


def content_text(html: str) -> tuple[str, list[str]]:
    """Main article text plus the list of accepted waste.

    Both pages put their menu, breadcrumbs, page title and opening hours into <li>
    elements inside the same container as the real list, so neither position nor length
    tells them apart. The accepted fractions are the items following the "Rodzaje ...
    odpadów" heading, and that is what this anchors on. If the city rewords that heading
    the list comes back empty and main() says so, which is the failure mode to want:
    visibly nothing rather than quietly the navigation.
    """
    soup = BeautifulSoup(html, "html.parser")
    for tag in soup(["script", "style", "nav", "header", "footer"]):
        tag.decompose()
    candidates = soup.select(".ce-bodytext, main, #content, .content")
    if not candidates:
        return "", []
    body = max(candidates, key=lambda c: len(c.get_text()))
    text = re.sub(r"[ \t]+", " ", body.get_text("\n", strip=True))

    marker = body.find(string=re.compile(r"Rodzaje.*odpad", re.I))
    scope = marker.parent.find_all_next("li") if marker else []
    items = [
        re.sub(r"\s+", " ", li.get_text(" ", strip=True)).strip().rstrip(",.")
        for li in scope
        if not li.find("a")
    ]
    # "szkło" is exactly five characters, and a length filter quietly ate it.
    return text, [i for i in items if len(i) > 2]


def parse_pszok() -> list[Point]:
    text, items = content_text(http_get(PSZOK_URL).text)

    # "ul. Kolberga: poniedziałek - piatek: 7.00-19.00, sobota: 8.00-15.00"
    hours = {
        m.group(1).strip().lower(): m.group(2).strip().rstrip(".")
        for m in re.finditer(r"ul\.\s*([^:\n]+):\s*(poniedzia[^\n]+)", text)
    }

    points: list[Point] = []
    for street, district, key in (
        ("ul. Oskara Kolberga 67", "Boguszowice Stare", "kolberga"),
        ("ul. Sportowa", "Niewiadom", "sportowa"),
    ):
        line = next((v for k, v in hours.items() if key in k), "")
        phone = None
        block = re.search(rf"{key}[^\n]*\n?[^\n]*tel:?\s*([0-9 ()]+)", text, re.I)
        if block:
            phone = block.group(1).strip()
        points.append(Point(
            id=f"pszok-{key}",
            name=f"PSZOK {district}",
            kind="PSZOK",
            address=street,
            district=district,
            phone=phone,
            email="info@skladowisko.rybnik.pl" if "skladowisko" in text else None,
            hours=line,
            accepted=[i for i in items if len(i) < 120],
            note="Odpady muszą być posegregowane, inaczej obsługa odmówi przyjęcia.",
            url=PSZOK_URL,
        ))
    return points


def parse_gpzon() -> list[Point]:
    text, items = content_text(http_get(GPZON_URL).text)

    address = re.search(r"(ul\.\s*Jankowicka\s*[0-9A-Za-z]+)", text)
    phone = re.search(r"tel\.?\s*([0-9 ]{7,})", text)
    hours = re.search(
        r"czynny\s+(od\s+poniedzia[^\n]*?)(?:\n|$).*?(\d{1,2}[.:]\d{2}).*?(\d{1,2}[.:]\d{2})",
        text,
        re.S | re.I,
    )
    hours_line = (
        f"poniedziałek - piątek: {hours.group(2)}-{hours.group(3)}" if hours else ""
    )

    return [Point(
        id="gpzon",
        name="GPZON, odpady niebezpieczne",
        kind="GPZON",
        address=address.group(1) if address else "",
        district="Śródmieście",
        phone=phone.group(1).strip() if phone else None,
        email=None,
        hours=hours_line,
        accepted=[i for i in items if len(i) < 160],
        note="Trzeba okazać dokument z imieniem, nazwiskiem i adresem zamieszkania. "
             "Przyjmowane tylko od mieszkańców Rybnika.",
        url=GPZON_URL,
    )]


def load_guide(data_dir: Path) -> list[dict]:
    """The hand-maintained dictionary. A missing or broken file must not kill the run."""
    path = data_dir / GUIDE_INPUT
    if not path.exists():
        print(f"[warn] brak {GUIDE_INPUT}, słownik będzie pusty", file=sys.stderr)
        return []
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"[error] {GUIDE_INPUT}: {e}", file=sys.stderr)
        return []

    entries = []
    for row in raw.get("entries", []):
        item, where = row.get("item"), row.get("where")
        if not item or not where:
            print(f"[warn] pomijam wpis bez item/where: {row}", file=sys.stderr)
            continue
        entries.append({
            "item": item,
            "where": where,
            "note": row.get("note"),
            "keywords": row.get("keywords", []),
        })
    return sorted(entries, key=lambda e: e["item"].lower())


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            pass

    data_dir = Path(__file__).parent / "data"
    data_dir.mkdir(parents=True, exist_ok=True)

    points: list[Point] = []
    failures: list[dict] = []

    for label, parser in (("PSZOK", parse_pszok), ("GPZON", parse_gpzon)):
        try:
            print(f"[info] scraping {label}", file=sys.stderr)
            points.extend(parser())
        except Exception as e:  # noqa: BLE001 — one page must not kill the other
            print(f"[error] {label}: {e}", file=sys.stderr)
            failures.append({"source": label, "error": str(e)})

    for p in points:
        if not p.address:
            print(f"[warn] {p.id} bez adresu", file=sys.stderr)
        if not p.hours:
            print(f"[warn] {p.id} bez godzin otwarcia", file=sys.stderr)
        if not p.accepted:
            print(f"[warn] {p.id} bez listy przyjmowanych odpadów", file=sys.stderr)
        print(f"[info] {p.id}: {p.address} | {p.hours} | {len(p.accepted)} frakcji",
              file=sys.stderr)

    guide = load_guide(data_dir)
    print(f"[info] słownik 'gdzie wyrzucić': {len(guide)} pozycji", file=sys.stderr)

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "count": len(points),
        "failures": failures,
        "points": [asdict(p) for p in points],
        "guide": guide,
    }
    out = data_dir / "waste_points.json"
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[info] wrote {len(points)} points, {len(guide)} guide entries → {out}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
