#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Grafiki do karty Mój Rybnik w Google Play.

    python play/make_store_assets.py

Wejście:  play/screenshots-916/raw-*/   surowe zrzuty z emulatorów 9:16
          (telefon 1080×1920, tablet 7" 1080×1920 @280 dpi, tablet 10" 1440×2560 @320 dpi)
Wyjście:  play/screenshots-916/phone/, tablet-7/, tablet-10/   zrzuty z podpisami
          play/graphics/icon-512.png                           ikona bez wewnętrznej ramki

Zasady Google Play, których pilnuje skrypt:
  - proporcje dokładnie 9:16, boki 320–3840 px (telefon) i 1080–7680 px (tablety), do 8 MB
  - podpis zajmuje najwyżej 20% wysokości; bez ramek urządzeń, bez „Pobierz teraz”, „#1”, cen
  - PNG bez przezroczystości
"""

import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

PLAY = Path(__file__).parent
SHOTS = PLAY / "screenshots-916"
FONTS = Path("C:/Windows/Fonts")

NAVY, NAVY_2 = (0, 62, 142), (0, 38, 92)       # tło ikony aplikacji i jego ciemniejsza wersja
WHITE, SKY = (255, 255, 255), (125, 200, 255)

CAPTIONS = {
    "01-start": ("Najważniejsze na jednym ekranie", "powietrze, komunikaty, wywóz i odjazdy"),
    "02-odpady": ("Wywóz odpadów pod Twój adres", "przypomnienie wieczorem dzień wcześniej"),
    "02-odjazdy": ("Odjazdy z Twojego przystanku", "rozkład KM Rybnik, także bez internetu"),
    "03-polaczenia": ("Połączenia skąd–dokąd", "również z przesiadką"),
    "03-kalendarz-odpadow": ("Kalendarz wywozów", "cały miesiąc dla Twojego adresu"),
    "04-wydarzenia": ("Co się dzieje w mieście", "koncerty, spektakle, wystawy i sport"),
    "05-wiadomosci": ("Lokalne wiadomości i komunikaty", "utrudnienia i awarie na górze listy"),
    "06-gdzie-wyrzucic": ("Gdzie to wyrzucić?", "słownik odpadów, PSZOK i GPZON"),
    "07-kalendarz-odpadow": ("Kalendarz wywozów", "cały miesiąc dla Twojego adresu"),
    "08-ciemny-motyw": ("Jasny i ciemny motyw", "bez reklam i bez zakładania konta"),
}

SETS = {  # katalog wejściowy → (wyjściowy, rozmiar płótna, zakres boków wg Google)
    "raw-phone": ("phone", (1080, 1920), (320, 3840)),
    "raw-tablet7": ("tablet-7", (1080, 1920), (1080, 7680)),
    "raw-tablet10": ("tablet-10", (1440, 2560), (1080, 7680)),
}

BAND = 0.1875      # pas z podpisem: 18,75% wysokości (limit Google to 20%)
MAX_BYTES = 8 * 1024 * 1024


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def background(w, h):
    top = Image.new("RGB", (w, h), NAVY)
    bottom = Image.new("RGB", (w, h), NAVY_2)
    mask = Image.linear_gradient("L").resize((w, h))
    return Image.composite(bottom, top, mask)


def fit_text(d, text, name, size, max_w):
    while size > 20:
        f = font(name, size)
        if d.textlength(text, font=f) <= max_w:
            return f
        size -= 2
    return font(name, size)


def compose(raw: Path, size):
    W, H = size
    s = W / 1080
    im = background(W, H)
    d = ImageDraw.Draw(im)
    title, sub = CAPTIONS[raw.stem]
    band = int(H * BAND)

    ft = fit_text(d, title, "segoeuib.ttf", int(66 * s), W - int(100 * s))
    fs = fit_text(d, sub, "segoeui.ttf", int(42 * s), W - int(100 * s))
    th = ft.getbbox(title)[3]
    sh = fs.getbbox(sub)[3]
    gap = int(18 * s)
    y0 = (band - (th + gap + sh)) // 2 + int(10 * s)
    d.text((W / 2, y0), title, font=ft, fill=WHITE, anchor="mt")
    d.text((W / 2, y0 + th + gap), sub, font=fs, fill=SKY, anchor="mt")

    shot = Image.open(raw).convert("RGB")
    top, bottom = band + int(24 * s), H - int(44 * s)
    sh_h = bottom - top
    sh_w = round(sh_h * shot.width / shot.height)
    shot = shot.resize((sh_w, sh_h), Image.LANCZOS)
    x = (W - sh_w) // 2
    r = int(34 * s)

    mask = Image.new("L", (sh_w, sh_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sh_w - 1, sh_h - 1], radius=r, fill=255)
    shadow = Image.new("L", (W, H), 0)
    shadow.paste(mask, (x, top + int(16 * s)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(int(28 * s))).point(lambda v: int(v * .55))
    im.paste(Image.new("RGB", (W, H), (0, 12, 30)), (0, 0), shadow)
    im.paste(shot, (x, top), mask)
    return im


def clean_icon():
    """Ikona bez wewnętrznego zaokrąglonego kwadratu: Play sam zaokrągla rogi."""
    src = PLAY / "graphics" / "icon-512-z-ramka.png"
    if not src.exists():
        (PLAY / "graphics" / "icon-512.png").rename(src)
    im = Image.open(src).convert("RGB")
    inner = Image.new("L", im.size, 0)
    ImageDraw.Draw(inner).rounded_rectangle([41, 40, 467, 465], radius=110, fill=255)
    art = ImageChops.difference(im, Image.new("RGB", im.size, NAVY)).convert("L").point(lambda v: 255 if v > 28 else 0)
    art = ImageChops.multiply(art, inner).filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.GaussianBlur(1.2))
    out = Image.new("RGB", im.size, NAVY)
    out.paste(im, (0, 0), art)
    out.save(PLAY / "graphics" / "icon-512.png", optimize=True)
    return out


def main():
    problems = []
    for src_dir, (dst_dir, size, (lo, hi)) in SETS.items():
        src, dst = SHOTS / src_dir, SHOTS / dst_dir
        dst.mkdir(exist_ok=True)
        raws = sorted(src.glob("*.png"))
        for raw in raws:
            out = dst / raw.name
            compose(raw, size).save(out, optimize=True)
            w, h = Image.open(out).size
            ok = w * 16 == h * 9 and lo <= min(w, h) and max(w, h) <= hi and out.stat().st_size <= MAX_BYTES
            if not ok:
                problems.append(out)
            print(f"  {dst_dir}/{out.name:<26} {w}×{h} {out.stat().st_size // 1024:>5} KB {'OK' if ok else 'BŁĄD'}")
        if len(raws) < (2 if dst_dir == "phone" else 4):
            problems.append(f"{dst_dir}: za mało zrzutów ({len(raws)})")

    icon = clean_icon()
    print(f"  graphics/icon-512.png {icon.size} {icon.mode}")
    if problems:
        sys.exit(f"Problemy: {problems}")
    print("Wszystko w limitach Google Play.")


if __name__ == "__main__":
    main()
