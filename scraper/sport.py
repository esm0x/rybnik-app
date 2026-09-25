#!/usr/bin/env python3
"""Rybnik sport fixtures and results → scraper/data/sport.json

Sources:
  * 90minut.pl  — football. ROW 1964 Rybnik (men, III liga gr. III) and
                  ROW Rybnik (k) (women). One page per club per season holds the
                  whole season: played matches with scores AND future fixtures
                  with kick-off times, league and cup together.
  * row.rybnik.com.pl — speedway (INNPRO ROW Rybnik, Metalkas 2. Ekstraliga).
                  The official schedule page carries the regular season only.

Pitfalls, all verified against the live sources:
  * 90minut.pl speaks HTTP only — port 443 refuses the connection outright — and
    serves ISO-8859-2 while declaring it properly, so requests' own guess is fine
    but must not be overridden with UTF-8.
  * `id_sezon` on 90minut grows by 2 per season (2021/22 = 99 … 2025/26 = 107) and
    the club page does NOT link the season in progress, so it cannot be scraped
    from there. It is computed and then verified by checking that the matches
    really fall inside the season window, with neighbouring ids as fallback.
  * Football scores are not always "2-1": a walkover reads "0-3 (wo)" and a cup tie
    decided on penalties reads "0-0k. 6-7". The extra text goes to `scoreNote`.
  * Away fixtures late in the season have a date but no kick-off time yet, hence
    `time` is nullable rather than faked as 00:00.
  * Speedway PLAY-OFFS ARE MISSING and there is no fix in sight. The schedule page
    is explicitly titled "rundy zasadniczej" and stops at round 14; the club's own
    front-page slider stops on the same date, so the 2026 semi-final against PSŻ
    Poznań (23.08 and 06.09) exists on the site only as photo-gallery captions with
    no score. Checked as alternatives: sportowefakty.wp.pl has no play-off rows in
    its markup, zuzelend.com's club page is a news feed with relative dates
    ("3 lata temu"), not a fixture table. The slider is parsed anyway so that
    anything the club does publish there lands in the data, but as of 2026 it adds
    nothing. The app must therefore say "po sezonie" instead of presenting a
    six-week-old result as the latest one.

Output shape is fixed by scraper/CONTRACT.md (sport.json) — Kotlin deserializes it
1:1, so field names and the enum values are binding.
"""

from __future__ import annotations

import json
import re
import sys
import time
import unicodedata
from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Optional

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)

BROWSER_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "pl-PL,pl;q=0.9",
}

POLITE_DELAY = 0.4

M90_BASE = "http://www.90minut.pl/mecze_druzyna.php"
# Anchor for the season arithmetic: 2025/26 was id_sezon=107, and the id grows by
# two each season. Verified against 99 = 2021/22 … 107 = 2025/26.
M90_ANCHOR_SEASON = 2025
M90_ANCHOR_ID = 107

SPEEDWAY_SCHEDULE = "https://row.rybnik.com.pl/druzyna/terminarz"
SPEEDWAY_HOME = "https://row.rybnik.com.pl/home"

PL_MONTHS = {
    "stycznia": 1, "lutego": 2, "marca": 3, "kwietnia": 4, "maja": 5, "czerwca": 6,
    "lipca": 7, "sierpnia": 8, "września": 9, "wrzesnia": 9, "października": 10,
    "pazdziernika": 10, "listopada": 11, "grudnia": 12,
}


@dataclass(frozen=True)
class Team:
    id: str
    name: str
    sport: str          # FOOTBALL | FOOTBALL_W | SPEEDWAY
    league: str
    url: str
    club_id: Optional[int] = None   # 90minut only
    # Substring that identifies this club in a fixture cell. "ROW Rybnik" alone
    # would match the women's team inside the men's table and the other way round.
    marker: str = ""


TEAMS = [
    Team(
        id="row-1964",
        name="ROW 1964 Rybnik",
        sport="FOOTBALL",
        league="III liga, gr. III",
        url="http://www.90minut.pl/skarb.php?id_klub=18912",
        club_id=18912,
        marker="ROW 1964 Rybnik",
    ),
    Team(
        id="row-kobiety",
        name="ROW Rybnik (k)",
        sport="FOOTBALL_W",
        league="III liga kobiet",
        url="http://www.90minut.pl/skarb.php?id_klub=20054",
        club_id=20054,
        marker="ROW Rybnik (k)",
    ),
    Team(
        id="row-zuzel",
        name="INNPRO ROW Rybnik",
        sport="SPEEDWAY",
        league="Metalkas 2. Ekstraliga",
        url=SPEEDWAY_SCHEDULE,
        marker="ROW Rybnik",
    ),
]


@dataclass
class Match:
    id: str
    teamId: str
    competition: str
    date: str                       # YYYY-MM-DD
    time: Optional[str]             # HH:MM, null when the kick-off is not set yet
    home: str
    away: str
    isHome: bool
    homeScore: Optional[int]
    awayScore: Optional[int]
    scoreNote: Optional[str]        # "wo", "k. 6-7" — anything beyond the plain score
    status: str                     # SCHEDULED | FINISHED


def http_get(url: str, encoding: Optional[str] = None) -> requests.Response:
    """GET with a short retry; one dead source must not kill the run."""
    last: Exception | None = None
    for attempt in range(3):
        try:
            r = requests.get(url, headers=BROWSER_HEADERS, timeout=30)
            r.raise_for_status()
            if encoding:
                r.encoding = encoding
            return r
        except requests.HTTPError as e:
            last = e
            status = e.response.status_code if e.response is not None else 0
            if status not in (403, 429, 500, 502, 503, 504):
                raise
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
        except requests.RequestException as e:
            last = e
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise last if last else RuntimeError(f"nie udało się pobrać {url}")


def slug(text: str) -> str:
    lowered = text.lower().replace("ł", "l")
    folded = "".join(
        c for c in unicodedata.normalize("NFKD", lowered) if not unicodedata.combining(c)
    )
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", folded)).strip("-")


def season_start_year(today: date) -> int:
    """Polish seasons run summer to summer, so July flips the year over."""
    return today.year if today.month >= 7 else today.year - 1


def parse_score(raw: str) -> tuple[Optional[int], Optional[int], Optional[str]]:
    """'2-1' → (2, 1, None); '0-3 (wo)' → (0, 3, 'wo'); '0-0k. 6-7' → (0, 0, 'k. 6-7')."""
    text = raw.strip()
    m = re.match(r"^(\d+)\s*[-:]\s*(\d+)(.*)$", text)
    if not m:
        return None, None, None
    note = m.group(3).strip(" ()").strip()
    return int(m.group(1)), int(m.group(2)), note or None


# --------------------------------------------------------------------------
# 90minut.pl — football
# --------------------------------------------------------------------------

def m90_rows(club_id: int, season_id: int) -> list[list[str]]:
    r = http_get(f"{M90_BASE}?id={club_id}&id_sezon={season_id}", encoding="iso-8859-2")
    soup = BeautifulSoup(r.text, "html.parser")
    rows: list[list[str]] = []
    for tr in soup.find_all("tr"):
        cells = [td.get_text(" ", strip=True) for td in tr.find_all("td")]
        cells = [c for c in cells if c]
        if len(cells) >= 4 and re.match(r"^\d{4}-\d{2}-\d{2}", cells[0]):
            rows.append(cells)
    return rows


def resolve_m90_season(club_id: int, today: date) -> tuple[int, list[list[str]]]:
    """Find the id_sezon whose matches actually fall inside the current season.

    The arithmetic alone would be a silent trap the day 90minut changes its
    numbering, so the guess is verified against the dates on the page and the
    neighbouring ids are tried before giving up.
    """
    start = season_start_year(today)
    guess = M90_ANCHOR_ID + 2 * (start - M90_ANCHOR_SEASON)
    window = (date(start, 7, 1), date(start + 1, 6, 30))

    for candidate in (guess, guess + 2, guess - 2, guess + 4, guess - 4):
        if candidate < 1:
            continue
        rows = m90_rows(club_id, candidate)
        inside = [
            r for r in rows
            if window[0] <= datetime.strptime(r[0][:10], "%Y-%m-%d").date() <= window[1]
        ]
        if inside:
            if candidate != guess:
                print(f"[warn] id_sezon {guess} był pusty, użyto {candidate}", file=sys.stderr)
            return candidate, inside
        time.sleep(POLITE_DELAY)

    raise RuntimeError(f"nie znalazłem sezonu {start}/{start + 1} dla klubu {club_id}")


def parse_football(team: Team, today: date) -> list[Match]:
    season_id, rows = resolve_m90_season(team.club_id, today)
    print(f"[info] {team.name}: id_sezon={season_id}, {len(rows)} meczów", file=sys.stderr)

    out: list[Match] = []
    for cells in rows:
        # 90minut puts date and kick-off in one cell, and drops the time when the
        # fixture has a date but no hour yet.
        stamp = cells[0]
        day = stamp[:10]
        hour = re.search(r"\b(\d{2}:\d{2})\b", stamp)
        competition, home, score, away = cells[1], cells[2], cells[3], cells[4]

        hs, aws, note = parse_score(score)
        is_home = team.marker in home
        out.append(Match(
            id=f"{team.id}-{day}-{slug(away if is_home else home)}",
            teamId=team.id,
            competition=competition,
            date=day,
            time=hour.group(1) if hour else None,
            home=home,
            away=away,
            isHome=is_home,
            homeScore=hs,
            awayScore=aws,
            scoreNote=note,
            status="FINISHED" if hs is not None else "SCHEDULED",
        ))
    return out


# --------------------------------------------------------------------------
# row.rybnik.com.pl — speedway
# --------------------------------------------------------------------------

def parse_speedway_schedule(team: Team) -> list[Match]:
    """The official table: round, date + time, both clubs, score. Regular season only."""
    r = http_get(SPEEDWAY_SCHEDULE)
    soup = BeautifulSoup(r.text, "html.parser")
    out: list[Match] = []

    for tr in soup.select("table.timetable-table tbody tr"):
        rnd = tr.select_one("td.timetable")
        when = tr.select("td.timetable")
        teams = tr.select("td.timetable-team")
        result = tr.select_one("td.timetable-result")
        if len(when) < 2 or len(teams) < 2:
            continue

        stamp = when[1].get_text("\n", strip=True)
        day = re.search(r"(\d{2})\.(\d{2})\.(\d{4})", stamp)
        if not day:
            continue
        iso = f"{day.group(3)}-{day.group(2)}-{day.group(1)}"
        hour = re.search(r"\b(\d{1,2}:\d{2})\b", stamp)

        home = teams[0].get_text(" ", strip=True)
        away = teams[1].get_text(" ", strip=True)
        hs, aws, note = parse_score(result.get_text(" ", strip=True) if result else "")
        is_home = team.marker in home
        out.append(Match(
            id=f"{team.id}-{iso}-{slug(away if is_home else home)}",
            teamId=team.id,
            competition=rnd.get_text(" ", strip=True) if rnd else team.league,
            date=iso,
            time=hour.group(1) if hour else None,
            home=home,
            away=away,
            isHome=is_home,
            homeScore=hs,
            awayScore=aws,
            scoreNote=note,
            status="FINISHED" if hs is not None else "SCHEDULED",
        ))
    return out


def parse_speedway_extra(team: Team) -> list[Match]:
    """Results slider on the front page.

    Dates come as Polish prose ("6 września 2026, godz. 15:15"), and the markup is
    a carousel rather than a table, so each slide is read on its own. In 2026 it
    carried nothing the schedule table did not already have — it is parsed as cheap
    insurance in case the club starts posting play-off results there.
    """
    r = http_get(SPEEDWAY_HOME)
    soup = BeautifulSoup(r.text, "html.parser")
    out: list[Match] = []

    for slide in soup.select(".previous-slider .slide, .previous-match .slide"):
        text = slide.get_text(" ", strip=True)
        when = re.search(r"(\d{1,2})\s+([a-ząćęłńóśźż]+)\s+(\d{4})", text, re.I)
        score = re.search(r"\b(\d{1,3})\s*:\s*(\d{1,3})\b", text)
        names = [n.get_text(" ", strip=True) for n in slide.select(".logo-box-name")]
        if not (when and score and len(names) >= 2):
            continue
        month = PL_MONTHS.get(when.group(2).lower())
        if not month:
            continue
        iso = f"{when.group(3)}-{month:02d}-{int(when.group(1)):02d}"
        hour = re.search(r"godz\.\s*(\d{1,2}:\d{2})", text)

        home, away = names[0], names[1]
        is_home = team.marker in home
        out.append(Match(
            id=f"{team.id}-{iso}-{slug(away if is_home else home)}",
            teamId=team.id,
            competition=team.league,
            date=iso,
            time=hour.group(1) if hour else None,
            home=home,
            away=away,
            isHome=is_home,
            homeScore=int(score.group(1)),
            awayScore=int(score.group(2)),
            scoreNote=None,
            status="FINISHED",
        ))
    return out


def merge(primary: list[Match], extra: list[Match]) -> list[Match]:
    """Schedule wins on competition names; the slider only adds what it misses."""
    known = {(m.date, m.teamId) for m in primary}
    added = [m for m in extra if (m.date, m.teamId) not in known]
    if added:
        print(f"[info] żużel: {len(added)} meczów spoza rundy zasadniczej", file=sys.stderr)
    return primary + added


def report(matches: list[Match], today: date) -> None:
    for team in TEAMS:
        mine = [m for m in matches if m.teamId == team.id]
        played = [m for m in mine if m.status == "FINISHED"]
        ahead = [m for m in mine if m.status == "SCHEDULED" and m.date >= today.isoformat()]
        nxt = min((m.date for m in ahead), default=None)
        print(
            f"[info] {team.name}: {len(mine)} meczów "
            f"({len(played)} rozegranych, najbliższy: {nxt or 'brak — po sezonie'})",
            file=sys.stderr,
        )
        if not mine:
            print(f"[warn] ZERO meczów dla {team.name} — parser albo źródło się zmieniło",
                  file=sys.stderr)


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001 — older/redirected streams
            pass

    today = date.today()
    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "sport.json"

    matches: list[Match] = []
    failures: list[dict] = []

    for team in TEAMS:
        try:
            if team.sport == "SPEEDWAY":
                print(f"[info] scraping {SPEEDWAY_SCHEDULE}", file=sys.stderr)
                scheduled = parse_speedway_schedule(team)
                try:
                    scheduled = merge(scheduled, parse_speedway_extra(team))
                except Exception as e:  # noqa: BLE001 — the slider is a bonus, not the source
                    print(f"[warn] slider na /home: {e}", file=sys.stderr)
                matches.extend(scheduled)
            else:
                print(f"[info] scraping 90minut dla {team.name}", file=sys.stderr)
                matches.extend(parse_football(team, today))
        except Exception as e:  # noqa: BLE001 — one dead source must not kill the run
            print(f"[error] {team.name}: {e}", file=sys.stderr)
            failures.append({"source": team.name, "error": str(e)})
        time.sleep(POLITE_DELAY)

    matches.sort(key=lambda m: (m.date, m.time or "99:99"))
    report(matches, today)

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "count": len(matches),
        "failures": failures,
        "teams": [
            {k: v for k, v in asdict(t).items() if k not in ("club_id", "marker")}
            for t in TEAMS
        ],
        "matches": [asdict(m) for m in matches],
    }
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[info] wrote {len(matches)} matches (failures: {len(failures)}) → {out_path}",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
