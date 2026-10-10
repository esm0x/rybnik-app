# Opis do Google Play — Mój Rybnik

Gotowe teksty do wklejenia w Play Console → **Grow → Store presence → Main store listing**.
Limity znaków są wymuszane przez Google; policzone wartości podane przy każdym polu.

---

## Nazwa aplikacji (max 30 znaków)

```
Mój Rybnik
```

*(10 znaków. Musi się zgadzać z `app_name` w `strings.xml` — inaczej pod ikoną i w
sklepie widać dwie różne nazwy.)*

---

## Krótki opis (max 80 znaków)

```
Wydarzenia, odpady, autobusy, smog i wyłączenia prądu. Bez reklam i kont.
```

*(73 znaki.)*

---

## Pełny opis (max 4000 znaków)

```
Mój Rybnik zbiera w jednym miejscu to, czego rybniczanin szuka w ciągu dnia: kiedy
jedzie autobus, kiedy zabiorą śmieci spod domu, co się dzieje wieczorem w mieście i
czy da się dziś otworzyć okno.

To niezależny projekt, a nie oficjalna aplikacja Urzędu Miasta Rybnika. Skąd
pochodzą dane, piszemy na końcu opisu.

CO ZNAJDZIESZ W APLIKACJI

Start
Jeden ekran z tym, co ważne dzisiaj: jakość powietrza, komunikaty o awariach i
utrudnieniach, najbliższy wywóz odpadów, odjazdy z Twojego przystanku, nadchodzące
wydarzenia i wynik ostatniego meczu rybnickiej drużyny.

Wydarzenia
Ponad sto wydarzeń zebranych z Teatru Ziemi Rybnickiej, iRybnika, biletyny, trzech
domów kultury i innych źródeł, z podziałem na koncerty, spektakle, kabarety, filmy,
wystawy i warsztaty. Wydarzenie można dodać do ulubionych i dostać przypomnienie
dzień wcześniej.

Transport
Pełny rozkład jazdy Komunikacji Miejskiej Rybnik: 626 przystanków, 44 linie, ponad
70 tysięcy odjazdów. Odliczanie do najbliższego autobusu, ulubione przystanki i
podgląd trasy linii. Rozkład zapisuje się na telefonie, więc działa też bez internetu.

Wyszukiwarka połączeń
Wpisz skąd i dokąd, a aplikacja pokaże konkretne kursy z godzinami, również z
przesiadką: którą linią jechać, gdzie się przesiąść i ile poczekać. Możesz podać całą
dzielnicę zamiast konkretnego przystanku.

Odpady
Harmonogram wywozu dla Twojego adresu. W Rybniku terminy zależą od ulicy, numeru domu
i typu zabudowy, a nie od dzielnicy, więc aplikacja pyta o konkretny numer i pokazuje
dokładnie Twój rejon, w formie listy albo kalendarza. Przypomnienie przychodzi
wieczorem dnia poprzedniego.

Wiadomości i komunikaty
Lokalne newsy z Radia 90, rybnik.com.pl, rybnik.eu, nowin i tuRybnika, a do tego
oficjalne ostrzeżenia IMGW dla Rybnika. Awarie, utrudnienia i ostrzeżenia trafiają na
górę listy i na ekran główny. Komunikat, który Cię nie dotyczy, możesz ukryć jednym
kliknięciem.

Gdzie wyrzucić
Wpisz przedmiot, a aplikacja powie, do którego pojemnika trafia i dlaczego: baterie,
styropian, choinka, paragon, żarówka. Do tego adresy i godziny obu rybnickich PSZOK-ów
oraz punktu odpadów niebezpiecznych GPZON, razem z listą tego, co przyjmują.

Widget na pulpicie
Najbliższy wywóz i aktualne PM10 bez otwierania aplikacji.

Jakość powietrza
Pomiary PM10 i PM2,5 ze stacji GIOŚ Rybnik-Borki wraz z indeksem jakości. Możesz
ustawić własny próg i dostać powiadomienie, gdy zostanie przekroczony.

Wyłączenia prądu
Planowane i awaryjne wyłączenia Tauron Dystrybucja, dopasowane do Twojego numeru domu,
a nie do całej dzielnicy.

Sport
Wyniki i terminarz rybnickich drużyn: ROW 1964 Rybnik, żużlowy INNPRO ROW Rybnik i
piłkarki ROW Rybnik.

DLACZEGO TAKA APLIKACJA

Bez reklam. Bez kont i logowania. Bez zbierania danych o użytkowniku i bez żadnego
naszego serwera. Harmonogram odpadów dopasowujemy do adresu wyłącznie na Twoim
telefonie. Jedyny wyjątek to wyłączenia prądu: żeby je sprawdzić, aplikacja pyta serwis
Tauron Dystrybucja o Twoją ulicę i numer domu, bo inaczej nie da się ich ustalić.

Po pierwszym uruchomieniu dane zapisują się lokalnie, więc aplikacja działa również
bez zasięgu. Jest jasny i ciemny motyw, do wyboru ręcznie albo zgodnie z ustawieniem
systemu.

ŹRÓDŁA DANYCH

KM Rybnik, km.rybnik.pl (rozkład jazdy); Miasto Rybnik i EKO Sp. z o.o., rybnik.eu
(odpady, PSZOK, GPZON); Główny Inspektorat Ochrony Środowiska, gios.gov.pl
(powietrze); IMGW, danepubliczne.imgw.pl (ostrzeżenia); Tauron Dystrybucja,
tauron-dystrybucja.pl (wyłączenia prądu); lokalne redakcje i instytucje kultury
(wydarzenia i wiadomości); 90minut.pl i kluby (sport).

Aplikacja jest projektem niezależnym i nie jest oficjalnym produktem Miasta Rybnik ani
żadnej z wymienionych instytucji. Dane prezentujemy w dobrej wierze, ale nie
gwarantujemy ich poprawności ani aktualności. W sprawach urzędowych zawsze sprawdź
źródło.

Błąd, brakujące wydarzenie albo pomysł na nową funkcję: office@admin-stack.com
```

*(3924 znaków. Do wklejenia bierz `play/opis-pelny.txt` — tu wiersze są łamane dla czytelności, a Play zachowałby każde złamanie.)*

---

## Kategoria i etykiety

- **Kategoria aplikacji:** Wiadomości i czasopisma
  *(tę samą wybrał halo! RYBNIK; alternatywa to „Podróże i informacje lokalne")*
- **Tagi:** wiadomości lokalne, transport publiczny, jakość powietrza
- **E-mail kontaktowy:** office@admin-stack.com
- **Polityka prywatności:** URL do `PRIVACY.md` (patrz `play/PUBLISHING.md`, krok 7)

---

## Co nowego (release notes, max 500 znaków)

Przy aktualizacji 1.1.1 podmień treść na:

```
Kliknięcie powiadomienia otwiera teraz aplikację na właściwym ekranie: komunikat
w Wiadomościach, przypomnienie o wywozie w Odpadach, alert smogowy w Jakości
powietrza. Wcześniej nie działo się nic.
```

Poniżej treść dla pierwszego wydania:

```
Pierwsze wydanie w Google Play.

Wydarzenia, rozkład jazdy KM Rybnik z wyszukiwarką połączeń skąd-dokąd, harmonogram
odpadów pod konkretny adres, wyszukiwarka „gdzie wyrzucić" z PSZOK-ami, lokalne
wiadomości i ostrzeżenia IMGW, jakość powietrza, wyłączenia prądu Tauron oraz wyniki
rybnickich drużyn. Widget na pulpit z najbliższym wywozem i smogiem. Powiadomienia
o wywozie, smogu i awariach. Jasny i ciemny motyw.
```

---

## Zrzuty ekranu

⚠️ **Stare zrzuty z `play/screenshots/` (1080 × 2280) się nie nadają.** Play Console
wymaga proporcji dokładnie 9:16 albo 16:9, a te mają 19:9. Zostają tylko jako archiwum.

Nowe zrzuty są w `play/screenshots-916/`, zrobione na wersji 1.2.5 na emulatorach
o proporcjach 9:16. Każdy ma podpis w górnym pasie, który zajmuje 18,75% wysokości
(Google pozwala na 20%). Nie mają ramek urządzeń ani haseł typu „Pobierz teraz”.
Przebudowa: `python play/make_store_assets.py` (surowe zrzuty w `raw-*`).

**Telefon** — `phone/`, 1080 × 1920, w tej kolejności (pierwsze trzy widać
w wynikach wyszukiwania):

1. `01-start.png` — Najważniejsze na jednym ekranie
2. `02-odpady.png` — Wywóz odpadów pod Twój adres
3. `03-polaczenia.png` — Połączenia skąd–dokąd
4. `04-wydarzenia.png` — Co się dzieje w mieście
5. `05-wiadomosci.png` — Lokalne wiadomości i komunikaty
6. `06-gdzie-wyrzucic.png` — Gdzie to wyrzucić?
7. `07-kalendarz-odpadow.png` — Kalendarz wywozów
8. `08-ciemny-motyw.png` — Jasny i ciemny motyw

**Tablet 7"** — `tablet-7/`, 1080 × 1920 (emulator 7", 617 dp szerokości).
**Tablet 10"** — `tablet-10/`, 1440 × 2560 (emulator 10", 720 dp szerokości).
Po cztery, bo tyle Google wymaga dla tabletów: start, odjazdy z przystanku,
kalendarz wywozów, wydarzenia. To prawdziwe zrzuty z tabletu: aplikacja nie ma
osobnego układu dla dużych ekranów, więc pokazują rozciągnięty układ z telefonu.

## Grafiki

- `play/graphics/icon-512.png` — ikona 512 × 512 **bez wewnętrznej ramki**. Poprzednia
  (`icon-512-z-ramka.png`) miała zaokrąglony kwadrat z obwódką, a Play sam zaokrągla
  rogi, więc w sklepie wyszłaby „ikona w ikonie”.
- `play/graphics/feature-graphic-1024x500.png` — grafika promocyjna 1024 × 500 (bez zmian)
