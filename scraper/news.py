#!/usr/bin/env python3
"""Aggregate Rybnik news → scraper/data/news.json

Sources:
  * rybnik.com.pl  — RSS 2.0, big and fresh (~300 items), no <category>
  * turybnik.pl    — RSS 2.0, has <category>, heavily advertorial → capped
  * nowiny.pl      — RSS 2.0, regional (Raciborz/Wodzislaw/Rybnik/Zory)
  * rybnik.eu      — NO RSS at all (every feed path 404s), so the TYPO3 news
                     lists are scraped directly. This is the official city
                     source and carries the outage/disruption notices, which
                     are the whole point of the module.

`priority` is the field that matters: ALERT (awarie, utrudnienia, ostrzezenia)
vs NORMAL. See ALERT_KEYWORDS below — that list is meant to be tuned.

Output shape is fixed by scraper/CONTRACT.md (news.json) — Kotlin deserializes
it 1:1, so field names and the ALERT/NORMAL enum values are binding.
"""

from __future__ import annotations

import collections
import hashlib
import html
import json
import re
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Iterable, Optional
from urllib.parse import urljoin, urlsplit, urlunsplit

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)

BROWSER_HEADERS = {
    "User-Agent": UA,
    "Accept": "application/rss+xml, application/xml;q=0.9, text/html;q=0.8, */*;q=0.7",
    "Accept-Language": "pl-PL,pl;q=0.9",
}

MAX_ITEMS = 120
SUMMARY_LEN = 300
POLITE_DELAY = 0.3
# NORMAL items older than this are dropped (turybnik.pl's feed still carries
# year-old SEO filler). ALERTs are never age-filtered.
MAX_AGE_DAYS = 365

# --------------------------------------------------------------------------
# ALERT detection — ONE list, tuned by hand. Matched against a diacritic-folded
# lowercase "title + summary", so stems are enough ("awari" catches awaria,
# awarii, awarie; "ostrzezeni" catches ostrzezenie/ostrzezenia).
# --------------------------------------------------------------------------
ALERT_KEYWORDS = [
    "awari",
    "utrudnieni",
    "ostrzezeni",
    "alarm",
    "brak wody",
    "brak pradu",
    "przerwa w dostaw",
    "wstrzymani dostaw",
    "zamknieci",
    "wylaczeni",
    "wylaczen",
    "ewakuacj",
    "skazeni",
]

# Words that only mean trouble in the right company. "objazd" alone flagged
# "Bosak rozpoczyna objazd po Polsce" — a campaign tour, not a detour — so it now
# needs a road word nearby. Add to this rather than to the list above when a keyword
# turns out to be ambiguous.
ALERT_KEYWORDS_IN_CONTEXT = {
    # Bare "ruch" is not usable as context — it matched "prezes Ruchu Narodowego".
    "objazd": (
        "ruchu drogow", "organizacji ruchu", "ulic", "drodze", "drogow",
        "kierowc", "skrzyzowani", "remont", "przejazd", "jezdni",
    ),
}
# Deliberately NOT here: "smog" (matches eco advertorials) and the bare stem
# "zamkniet" (matches "zamknietych drzwiach" and similar prose).

PRIORITY_ALERT = "ALERT"
PRIORITY_NORMAL = "NORMAL"


@dataclass
class NewsItem:
    id: str
    title: str
    summary: str
    link: str
    published: str      # ISO 8601 local, minute precision: YYYY-MM-DDTHH:MM
    source: str         # human-readable domain
    category: str
    priority: str       # ALERT | NORMAL


@dataclass
class RssSource:
    source: str          # value written into `source`
    url: str
    default_category: str
    use_feed_category: bool = False
    cap: Optional[int] = None   # cap NORMAL items from this source (ALERTs never capped)


RSS_SOURCES = [
    # Radio 90 is regional (Wodzisław / Racibórz / Żory / Jastrzębie / Cieszyn too), so use
    # the Rybnik *tag* feed — /category/rybnik/feed exists but returns zero items. Breaking
    # news sometimes carries every city tag at once, so this over-includes a little.
    # Canonical host is www; the bare domain 301-redirects.
    RssSource("Radio 90", "https://www.radio90.pl/tag/rybnik/feed", "Wiadomości", cap=40),
    RssSource("rybnik.com.pl", "https://www.rybnik.com.pl/feed,feed0.html", "Wiadomości", cap=55),
    # Stale (newest item is months old) and largely advertorial — keep it, but
    # do not let it push out fresh city news.
    RssSource("tuRybnik.pl", "https://turybnik.pl/feed/", "Wiadomości", use_feed_category=True, cap=12),
    RssSource("nowiny.pl", "https://nowiny.pl/feed/", "Region", cap=15),
]

RYBNIK_EU = "https://www.rybnik.eu"


@dataclass
class EuSection:
    path: str
    category: str
    force_alert: bool = False
    pages: int = 1


# rybnik.eu TYPO3 news lists. `komunikaty` is the official notice board →
# everything there is an ALERT by contract, the rest goes through keywords.
EU_SECTIONS = [
    # Not an alert board in practice — it also carries ordinary notices ("mieszkanie
    # czeka na najemce"). Forcing ALERT here drowned the real outages, so this section
    # goes through the keyword list like everything else.
    EuSection("/dla-mieszkancow/aktualnosci/kategoria/komunikaty", "Komunikaty", force_alert=False, pages=2),
    EuSection("/dla-mieszkancow/aktualnosci", "Aktualności", pages=2),
    EuSection("/dla-mieszkancow/aktualnosci/kategoria/komunikacja", "Komunikacja", pages=1),
    EuSection("/dla-mieszkancow/aktualnosci/kategoria/inwestycje", "Inwestycje", pages=1),
]


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def http_get(url: str) -> requests.Response:
    """GET with a short retry.

    rybnik.com.pl answers 403 to GitHub Actions runners while serving the same URL
    fine from a home connection, so the block is on the datacenter IP range, not on
    the User-Agent (verified: the bot UA gets 200 from a residential address).
    Retrying helps only when the refusal is rate-limiting rather than a hard block —
    when it is not, the caller records the failure and the other sources still run.
    """
    last: Exception | None = None
    for attempt in range(3):
        try:
            r = requests.get(url, headers=BROWSER_HEADERS, timeout=30)
            r.raise_for_status()
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


def fold(text: str) -> str:
    """lowercase + strip Polish diacritics, so keyword stems stay ASCII."""
    lowered = text.lower().replace("ł", "l")
    decomposed = unicodedata.normalize("NFKD", lowered)
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def strip_html(raw: Optional[str]) -> str:
    if not raw:
        return ""
    text = BeautifulSoup(html.unescape(raw), "html.parser").get_text(" ", strip=True)
    return " ".join(text.split())


def truncate(text: str, limit: int = SUMMARY_LEN) -> str:
    if len(text) <= limit:
        return text
    cut = text[:limit]
    space = cut.rfind(" ")
    if space > limit * 0.6:
        cut = cut[:space]
    return cut.rstrip(" .,;:–-") + "…"


def item_id(link: str) -> str:
    return hashlib.sha1(link.encode("utf-8")).hexdigest()


def normalize_link(link: str) -> str:
    """Canonical form used for dedupe: drop query/fragment, trailing slash, scheme."""
    parts = urlsplit(link.strip())
    netloc = parts.netloc.lower().removeprefix("www.")
    path = parts.path.rstrip("/")
    return urlunsplit(("", netloc, path, "", ""))


def title_key(title: str) -> str:
    """Near-identical-title key: folded, alphanumerics only."""
    return re.sub(r"[^a-z0-9]+", "", fold(title))


def parse_rfc822(raw: Optional[str]) -> Optional[str]:
    """RFC-822 → ISO local naive 'YYYY-MM-DDTHH:MM'. None if unparseable."""
    if not raw or not raw.strip():
        return None
    try:
        dt = parsedate_to_datetime(raw.strip())
    except (TypeError, ValueError):
        return None
    if dt is None:
        return None
    if dt.tzinfo is not None:
        dt = dt.astimezone().replace(tzinfo=None)
    return dt.isoformat(timespec="minutes")


def classify(title: str, summary: str, force_alert: bool = False) -> str:
    if force_alert:
        return PRIORITY_ALERT
    haystack = fold(f"{title} {summary}")
    for kw in ALERT_KEYWORDS:
        if kw in haystack:
            return PRIORITY_ALERT
    for kw, context in ALERT_KEYWORDS_IN_CONTEXT.items():
        if kw in haystack and any(c in haystack for c in context):
            return PRIORITY_ALERT
    return PRIORITY_NORMAL


# --------------------------------------------------------------------------
# RSS
# --------------------------------------------------------------------------

def rss_text(item: ET.Element, tag: str) -> Optional[str]:
    el = item.find(tag)
    return el.text if el is not None and el.text else None


def parse_rss(src: RssSource) -> list[NewsItem]:
    resp = http_get(src.url)
    root = ET.fromstring(resp.content)  # bytes → lets ET honour the XML decl
    items: list[NewsItem] = []
    skipped_dates = 0

    for node in root.findall(".//item"):
        link = (rss_text(node, "link") or rss_text(node, "guid") or "").strip()
        title = strip_html(rss_text(node, "title"))
        if not link or not title:
            continue

        published = parse_rfc822(rss_text(node, "pubDate"))
        if not published:
            # Never invent now() — an item with a bogus date would jump the list.
            skipped_dates += 1
            continue

        summary = truncate(strip_html(rss_text(node, "description")))
        category = src.default_category
        if src.use_feed_category:
            feed_cat = strip_html(rss_text(node, "category"))
            if feed_cat:
                category = feed_cat

        items.append(NewsItem(
            id=item_id(link),
            title=title,
            summary=summary,
            link=link,
            published=published,
            source=src.source,
            category=category,
            priority=classify(title, summary),
        ))

    if skipped_dates:
        print(f"[warn] {src.source}: skipped {skipped_dates} item(s) with unparseable pubDate",
              file=sys.stderr)
    print(f"[info] {src.source}: parsed {len(items)} items", file=sys.stderr)
    return items


# --------------------------------------------------------------------------
# rybnik.eu (TYPO3 HTML, no feed)
# --------------------------------------------------------------------------

def eu_teaser(url: str, cache: dict[str, str]) -> str:
    """The list view carries no teaser, so pull the detail page's meta description."""
    if url in cache:
        return cache[url]
    teaser = ""
    try:
        soup = BeautifulSoup(http_get(url).text, "html.parser")
        meta = (soup.select_one('meta[name="description"]')
                or soup.select_one('meta[property="og:description"]'))
        if meta and meta.get("content"):
            teaser = strip_html(meta["content"])
        if not teaser:
            for p in soup.select("p"):
                text = p.get_text(" ", strip=True)
                if len(text) >= 60:
                    teaser = " ".join(text.split())
                    break
    except Exception as e:  # noqa: BLE001 — a missing teaser must not kill the item
        print(f"[warn] teaser failed {url}: {e}", file=sys.stderr)
    teaser = truncate(teaser)
    cache[url] = teaser
    time.sleep(POLITE_DELAY)
    return teaser


def parse_eu_section(section: EuSection, teaser_cache: dict[str, str]) -> list[NewsItem]:
    items: list[NewsItem] = []
    seen: set[str] = set()

    for page in range(1, section.pages + 1):
        path = section.path if page == 1 else f"{section.path}/page-{page}"
        url = urljoin(RYBNIK_EU, path)
        soup = BeautifulSoup(http_get(url).text, "html.parser")
        articles = soup.select("article")
        print(f"[info] rybnik.eu {path}: {len(articles)} articles", file=sys.stderr)

        for art in articles:
            heading = art.find(["h2", "h3"])
            anchor = heading.find("a", href=True) if heading else None
            if not anchor:
                continue
            link = urljoin(RYBNIK_EU, anchor["href"])
            title = " ".join(anchor.get_text(" ", strip=True).split())
            if not title or link in seen:
                continue

            time_el = art.find("time")
            raw_date = (time_el.get("datetime") if time_el else None) or ""
            date_match = re.match(r"(\d{4})-(\d{2})-(\d{2})", raw_date.strip())
            if not date_match:
                print(f"[warn] rybnik.eu: no usable date for {link}", file=sys.stderr)
                continue
            # TYPO3 gives date only; 00:00 keeps the ISO shape the contract wants.
            published = f"{date_match.group(0)}T00:00"

            cat_el = art.select_one("span.category")
            category = (cat_el.get_text(" ", strip=True) if cat_el else "") or section.category

            summary = eu_teaser(link, teaser_cache)
            seen.add(link)
            items.append(NewsItem(
                id=item_id(link),
                title=title,
                summary=summary,
                link=link,
                published=published,
                source="rybnik.eu",
                category=category,
                priority=classify(title, summary, force_alert=section.force_alert),
            ))
        time.sleep(POLITE_DELAY)

    return items


# --------------------------------------------------------------------------
# merge
# --------------------------------------------------------------------------

def dedupe(items: Iterable[NewsItem]) -> list[NewsItem]:
    """First occurrence wins; ALERTs are processed first so they survive a clash."""
    ordered = sorted(items, key=lambda i: i.published, reverse=True)
    ordered.sort(key=lambda i: i.priority != PRIORITY_ALERT)  # stable: ALERTs first, newest first
    out: list[NewsItem] = []
    seen_links: set[str] = set()
    seen_titles: set[str] = set()
    for it in ordered:
        lkey, tkey = normalize_link(it.link), title_key(it.title)
        if lkey in seen_links or (tkey and tkey in seen_titles):
            continue
        seen_links.add(lkey)
        seen_titles.add(tkey)
        out.append(it)
    return out


def apply_caps(items: list[NewsItem], caps: dict[str, int]) -> list[NewsItem]:
    """Per-source cap + age cut-off, on NORMAL items only — ALERTs are kept."""
    cutoff = (datetime.now() - timedelta(days=MAX_AGE_DAYS)).isoformat(timespec="minutes")
    kept: list[NewsItem] = []
    used: dict[str, int] = {}
    for it in sorted(items, key=lambda i: i.published, reverse=True):
        if it.priority == PRIORITY_ALERT:
            kept.append(it)
            continue
        if it.published < cutoff:
            continue
        cap = caps.get(it.source)
        n = used.get(it.source, 0)
        if cap is not None and n >= cap:
            continue
        used[it.source] = n + 1
        kept.append(it)
    return kept


def enforce_total(items: list[NewsItem], limit: int = MAX_ITEMS) -> list[NewsItem]:
    """Cap the total, but NEVER drop an ALERT to make room for a NORMAL.

    The leftover room is filled round-robin across sources rather than by a flat
    date sort: turybnik.pl is stale by months and a flat sort wipes it out
    entirely, while rybnik.com.pl (300+ fresh items) would take every slot.
    """
    alerts = sorted([i for i in items if i.priority == PRIORITY_ALERT],
                    key=lambda i: i.published, reverse=True)
    room = max(0, limit - len(alerts))

    queues: dict[str, list[NewsItem]] = {}
    for it in sorted([i for i in items if i.priority != PRIORITY_ALERT],
                     key=lambda i: i.published, reverse=True):
        queues.setdefault(it.source, []).append(it)

    # Freshest source first, so it also wins the leftover slot in the last round.
    order = sorted(queues, key=lambda s: queues[s][0].published, reverse=True)
    picked: list[NewsItem] = []
    while len(picked) < room and any(queues[s] for s in order):
        for src in order:
            if len(picked) >= room:
                break
            if queues[src]:
                picked.append(queues[src].pop(0))

    merged = alerts + picked
    merged.sort(key=lambda i: i.published, reverse=True)
    return merged


# --------------------------------------------------------------------------
# verification output
# --------------------------------------------------------------------------

def report(items: list[NewsItem]) -> None:
    print("\n=== news.json verification ===", file=sys.stderr)
    per_source: dict[str, int] = {}
    for it in items:
        per_source[it.source] = per_source.get(it.source, 0) + 1
    for src, n in sorted(per_source.items(), key=lambda kv: -kv[1]):
        print(f"[info] source {src:>16}: {n}", file=sys.stderr)

    alerts = [i for i in items if i.priority == PRIORITY_ALERT]
    print(f"[info] priority ALERT={len(alerts)}  NORMAL={len(items) - len(alerts)}", file=sys.stderr)
    if items:
        print(f"[info] newest published: {items[0].published}", file=sys.stderr)
        print(f"[info] oldest published: {items[-1].published}", file=sys.stderr)

    if not alerts:
        print("[warn] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", file=sys.stderr)
        print("[warn] ZERO ALERT ITEMS — the rybnik.eu scraper or the keyword", file=sys.stderr)
        print("[warn] list is broken. The whole point of this module is gone.", file=sys.stderr)
        print("[warn] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", file=sys.stderr)
    else:
        print("[info] sample ALERT items:", file=sys.stderr)
        for it in alerts[:3]:
            print(f"[info]   {it.published} [{it.source}/{it.category}] {it.title[:80]}", file=sys.stderr)


def carry_over_failed(
    collected: list[NewsItem],
    failures: list[dict],
    out_path: Path,
) -> list[NewsItem]:
    """Reuse the previous run's items for any source that failed this time.

    rybnik.com.pl answers 403 to GitHub Actions runners while serving fine from a home
    connection, and it is the source of most ALERTs. Without this, every CI run replaced
    a good 120-item file with a 75-item one that had no current alerts at all — the app's
    home screen then had nothing to show. Keeping the last known items for a failed source
    means a blocked feed degrades slowly instead of wiping the section.
    """
    if not failures or not out_path.exists():
        return []

    failed = {f["source"] for f in failures}
    try:
        previous = json.loads(out_path.read_text(encoding="utf-8")).get("items", [])
    except (OSError, json.JSONDecodeError) as e:
        print(f"[warn] cannot reuse previous news.json: {e}", file=sys.stderr)
        return []

    have = {i.link for i in collected}
    revived: list[NewsItem] = []
    for raw in previous:
        if raw.get("source") not in failed or raw.get("link") in have:
            continue
        try:
            revived.append(NewsItem(**raw))
        except TypeError:
            continue

    if revived:
        by_source = collections.Counter(i.source for i in revived)
        print(f"[warn] reusing previous items for failed sources: {dict(by_source)}",
              file=sys.stderr)
    return revived


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001 — older/redirected streams
            pass

    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "news.json"

    collected: list[NewsItem] = []
    failures: list[dict] = []

    for src in RSS_SOURCES:
        try:
            print(f"[info] fetching RSS {src.url}", file=sys.stderr)
            collected.extend(parse_rss(src))
        except Exception as e:  # noqa: BLE001 — one dead feed must not kill the run
            print(f"[error] {src.source}: {e}", file=sys.stderr)
            failures.append({"source": src.source, "error": str(e)})
        time.sleep(POLITE_DELAY)

    teaser_cache: dict[str, str] = {}
    for section in EU_SECTIONS:
        try:
            print(f"[info] scraping rybnik.eu {section.path}", file=sys.stderr)
            collected.extend(parse_eu_section(section, teaser_cache))
        except Exception as e:  # noqa: BLE001
            print(f"[error] rybnik.eu {section.path}: {e}", file=sys.stderr)
            failures.append({"source": f"rybnik.eu{section.path}", "error": str(e)})

    collected += carry_over_failed(collected, failures, out_path)

    caps = {s.source: s.cap for s in RSS_SOURCES if s.cap is not None}
    items = enforce_total(apply_caps(dedupe(collected), caps))

    report(items)

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "count": len(items),
        "failures": failures,
        "items": [asdict(i) for i in items],
    }
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[info] wrote {len(items)} items (failures: {len(failures)}) → {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
