#!/usr/bin/env python3
"""Resolve + sanity-check the KM Rybnik GTFS feed → scraper/data/transit_meta.json

The timetable is far too big for JSON, so the app downloads `gtfs.zip` itself.
This script's only job is to say WHERE the current edition lives and to prove it
is usable. The zip is downloaded here for verification only — never committed.

Operator is KM Rybnik (km.rybnik.pl). ZTZ Rybnik is defunct; ignore it.

The attachment id changes every edition (629 = Aug, 633 = Sep), so it is
scraped from the server-rendered downloads page, never hardcoded.

Known quirks of this feed — checked on every run and printed as [warn], because
the Android GTFS reader has to cope with all three:
  1. shapes.txt is header-only → no route geometry at all.
  2. calendar.txt has every weekday flag = 0; real service lives entirely in
     calendar_dates.txt (exception_type=1). A naive parser → ZERO departures.
  3. One route has route_short_name "-->" (a leaked HTML comment) but carries
     ~87 real trips, so it must NOT be blindly dropped.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import re
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional

import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)

BASE = "https://km.rybnik.pl"
DOWNLOADS_PAGE = f"{BASE}/435/pliki-do-pobrania.html"
ATTACHMENT_RX = re.compile(r'/download/attachment/(\d+)/[^"\'>]*gtfs[^"\'>]*', re.IGNORECASE)
FALLBACK_ATTACHMENT_ID = 633  # last known-good edition (September 2026)

REQUIRED_MEMBERS = [
    "agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt",
    "calendar.txt", "calendar_dates.txt", "shapes.txt", "feed_info.txt",
]

WEEKDAY_FIELDS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
BROKEN_SHORT_NAME = "-->"


@dataclass
class Quirks:
    shapes_header_only: bool = False
    calendar_all_zero: bool = False
    html_comment_route: bool = False
    html_comment_route_trips: int = 0


def gtfs_url_for(attachment_id: int) -> str:
    # The filename segment is cosmetic — the server keys off the attachment id.
    return f"{BASE}/download/attachment/{attachment_id}/gtfs.zip"


def resolve_attachment_id(failures: list[dict]) -> tuple[int, bool]:
    """Scrape the downloads page for the newest gtfs attachment id."""
    try:
        r = requests.get(DOWNLOADS_PAGE, headers={"User-Agent": UA}, timeout=30)
        r.raise_for_status()
        ids = [int(m) for m in ATTACHMENT_RX.findall(r.text)]
        if ids:
            best = max(ids)
            print(f"[info] downloads page lists gtfs attachment id(s) {sorted(set(ids))} → using {best}",
                  file=sys.stderr)
            return best, True
        raise ValueError("no /download/attachment/<id>/...gtfs link on the page")
    except Exception as e:  # noqa: BLE001 — never let this kill the run
        print(f"[warn] attachment id resolution failed ({e}); "
              f"falling back to known-good {FALLBACK_ATTACHMENT_ID}", file=sys.stderr)
        failures.append({
            "source": DOWNLOADS_PAGE,
            "error": f"could not resolve gtfs attachment id ({e}); "
                     f"fell back to hardcoded id {FALLBACK_ATTACHMENT_ID}",
        })
        return FALLBACK_ATTACHMENT_ID, False


def read_rows(zf: zipfile.ZipFile, member: str) -> list[dict]:
    with zf.open(member) as fh:
        text = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
        return [row for row in csv.DictReader(text)]


def as_date(raw: Optional[str]) -> Optional[str]:
    """GTFS YYYYMMDD → YYYY-MM-DD."""
    if not raw:
        return None
    raw = raw.strip()
    if not re.fullmatch(r"\d{8}", raw):
        return None
    return f"{raw[0:4]}-{raw[4:6]}-{raw[6:8]}"


def validity_window(zf: zipfile.ZipFile, failures: list[dict]) -> tuple[Optional[str], Optional[str]]:
    try:
        info = read_rows(zf, "feed_info.txt")
        if info:
            start = as_date(info[0].get("feed_start_date"))
            end = as_date(info[0].get("feed_end_date"))
            if start and end:
                return start, end
            print("[warn] feed_info.txt has no usable feed_start_date/feed_end_date", file=sys.stderr)
    except Exception as e:  # noqa: BLE001
        print(f"[warn] feed_info.txt unreadable: {e}", file=sys.stderr)
        failures.append({"source": "feed_info.txt", "error": str(e)})

    # Fallback: the real service calendar.
    try:
        dates = [as_date(r.get("date")) for r in read_rows(zf, "calendar_dates.txt")]
        dates = sorted(d for d in dates if d)
        if dates:
            print("[info] validity window taken from calendar_dates.txt (feed_info fallback)",
                  file=sys.stderr)
            return dates[0], dates[-1]
    except Exception as e:  # noqa: BLE001
        failures.append({"source": "calendar_dates.txt", "error": str(e)})
    return None, None


def check_quirks(zf: zipfile.ZipFile) -> Quirks:
    q = Quirks()

    shapes = read_rows(zf, "shapes.txt")
    q.shapes_header_only = len(shapes) == 0

    calendar = read_rows(zf, "calendar.txt")
    q.calendar_all_zero = bool(calendar) and all(
        (row.get(day) or "0").strip() == "0" for row in calendar for day in WEEKDAY_FIELDS
    )

    routes = read_rows(zf, "routes.txt")
    broken = [r for r in routes if (r.get("route_short_name") or "").strip() == BROKEN_SHORT_NAME]
    if broken:
        q.html_comment_route = True
        broken_ids = {r.get("route_id") for r in broken}
        trips = read_rows(zf, "trips.txt")
        q.html_comment_route_trips = sum(1 for t in trips if t.get("route_id") in broken_ids)

    return q


def report_quirks(q: Quirks, active_services: int) -> None:
    print("\n=== known data quirks (Android must handle these) ===", file=sys.stderr)
    if q.shapes_header_only:
        print("[warn] QUIRK 1 CONFIRMED: shapes.txt is header-only — no route geometry at all.",
              file=sys.stderr)
    else:
        print("[info] quirk 1 NOT reproduced: shapes.txt has data now.", file=sys.stderr)

    if q.calendar_all_zero:
        print("[warn] QUIRK 2 CONFIRMED: every weekday flag in calendar.txt is 0. Service comes "
              f"solely from calendar_dates.txt ({active_services} rows with exception_type=1). "
              "A naive GTFS parser yields ZERO departures.", file=sys.stderr)
    else:
        print("[info] quirk 2 NOT reproduced: calendar.txt has non-zero weekday flags.", file=sys.stderr)

    if q.html_comment_route:
        print(f"[warn] QUIRK 3 CONFIRMED: a route has route_short_name '{BROKEN_SHORT_NAME}' "
              f"(leaked HTML comment) yet carries {q.html_comment_route_trips} real trips — "
              "do NOT blindly drop it.", file=sys.stderr)
    else:
        print(f"[info] quirk 3 NOT reproduced: no route named '{BROKEN_SHORT_NAME}'.", file=sys.stderr)


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            pass

    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "transit_meta.json"

    failures: list[dict] = []
    attachment_id, resolved = resolve_attachment_id(failures)
    url = gtfs_url_for(attachment_id)

    print(f"[info] downloading {url}", file=sys.stderr)
    try:
        resp = requests.get(url, headers={"User-Agent": UA}, timeout=120)
        resp.raise_for_status()
        blob = resp.content
    except Exception as e:  # noqa: BLE001
        print(f"[error] download failed: {e}", file=sys.stderr)
        failures.append({"source": url, "error": str(e)})
        if resolved and attachment_id != FALLBACK_ATTACHMENT_ID:
            url = gtfs_url_for(FALLBACK_ATTACHMENT_ID)
            attachment_id = FALLBACK_ATTACHMENT_ID
            print(f"[warn] retrying with known-good {url}", file=sys.stderr)
            resp = requests.get(url, headers={"User-Agent": UA}, timeout=120)
            resp.raise_for_status()
            blob = resp.content
        else:
            raise

    sha256 = hashlib.sha256(blob).hexdigest()
    print(f"[info] got {len(blob)} bytes, sha256={sha256}", file=sys.stderr)

    # The zip is verification-only; it is never written to disk / committed.
    zf = zipfile.ZipFile(io.BytesIO(blob))
    names = set(zf.namelist())
    missing = [m for m in REQUIRED_MEMBERS if m not in names]
    if missing:
        print(f"[error] missing GTFS members: {missing}", file=sys.stderr)
        failures.append({"source": url, "error": f"missing GTFS members: {', '.join(missing)}"})
    else:
        print(f"[info] all {len(REQUIRED_MEMBERS)} required members present", file=sys.stderr)

    stops = read_rows(zf, "stops.txt") if "stops.txt" in names else []
    routes = read_rows(zf, "routes.txt") if "routes.txt" in names else []
    trips = read_rows(zf, "trips.txt") if "trips.txt" in names else []
    cal_dates = read_rows(zf, "calendar_dates.txt") if "calendar_dates.txt" in names else []
    active = sum(1 for r in cal_dates if (r.get("exception_type") or "").strip() == "1")

    print(f"[info] rows: stops={len(stops)} routes={len(routes)} trips={len(trips)} "
          f"calendar_dates={len(cal_dates)}", file=sys.stderr)
    if not stops or not routes:
        failures.append({"source": url, "error": "stops.txt or routes.txt is empty"})

    valid_from, valid_to = validity_window(zf, failures)
    print(f"[info] validity window: {valid_from} → {valid_to}", file=sys.stderr)
    if not valid_from or not valid_to:
        failures.append({"source": url, "error": "could not determine feed validity window"})

    quirks = check_quirks(zf)
    report_quirks(quirks, active)

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "gtfs_url": url,
        "attachment_id": attachment_id,
        "valid_from": valid_from,
        "valid_to": valid_to,
        "stop_count": len(stops),
        "route_count": len(routes),
        "sha256": sha256,
        "failures": failures,
    }
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n[info] wrote transit_meta.json (failures: {len(failures)}) → {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
