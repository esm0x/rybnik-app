#!/usr/bin/env python3
"""Copy Rybnik's data from miasto-engine into scraper/data/ for the 1.3.x app.

The engine checks every file against the data contract before publishing it, but this
copy is what installed apps read, so it checks again for the one failure that would
hurt: a file that does not parse, or one whose main list has gone empty. Such a file is
skipped and the previous copy stays; the run then fails so it shows up red in Actions.

announcement.json is mirrored too: the engine's data/rybnik/announcement.json is the
single place to write a message to Rybnik's users, old app versions included.
"""

from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

SOURCE = "https://raw.githubusercontent.com/esm0x/miasto-engine/main/data/rybnik/"
TARGET = Path(__file__).resolve().parents[1] / "scraper" / "data"

# file → the list that must not be empty (None: any valid JSON object will do)
FILES = {
    "events.json": "events",
    "news.json": "items",
    "sport.json": "teams",
    "waste.json": "rejony",
    "waste_points.json": "points",
    "transit_meta.json": None,
    "announcement.json": None,
}
MAX_BYTES = 20 * 1024 * 1024


def fetch(name: str) -> bytes:
    req = urllib.request.Request(SOURCE + name, headers={"User-Agent": "rybnik-app-mirror"})
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read(MAX_BYTES + 1)
    if len(body) > MAX_BYTES:
        raise ValueError(f"over {MAX_BYTES} bytes")
    return body


def main() -> int:
    TARGET.mkdir(parents=True, exist_ok=True)
    problems = []
    for name, required in FILES.items():
        try:
            body = fetch(name)
            data = json.loads(body.decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("not a JSON object")
            if required and not data.get(required):
                raise ValueError(f"'{required}' is missing or empty")
            if name == "transit_meta.json" and not str(data.get("gtfs_url", "")).startswith("https://"):
                raise ValueError("gtfs_url is not https")
        except Exception as e:  # noqa: BLE001 — keep the previous copy, report at the end
            problems.append(f"{name}: {e}")
            print(f"[skip] {name}: {e}", file=sys.stderr)
            continue
        out = TARGET / name
        new = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        old = out.read_text(encoding="utf-8") if out.exists() else ""
        if new != old:
            out.write_text(new, encoding="utf-8")
            print(f"[copy] {name}")
        else:
            print(f"[same] {name}")
    if problems:
        print("\n".join(["", "NOT COPIED:", *problems]), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
