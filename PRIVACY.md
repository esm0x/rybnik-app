# Polityka prywatności — Mój Rybnik

Obowiązuje od 26 września 2026. Dotyczy aplikacji Android **Mój Rybnik**
(identyfikator pakietu `eu.rybnik.events`).

## Krótko

Aplikacja **nie zbiera żadnych danych o użytkowniku**, nie zakłada kont, nie ma reklam
ani narzędzi analitycznych. Nie ma serwera, na który cokolwiek by wysyłała. Wszystko,
co ustawisz, zostaje na Twoim telefonie.

## Jakie dane przechowuje aplikacja

Wyłącznie lokalnie, w pamięci aplikacji na Twoim urządzeniu:

- **adres do harmonogramu odpadów** (dzielnica, ulica, numer domu, typ zabudowy) —
  podajesz go sam, służy do wybrania właściwego rejonu wywozu i do sprawdzania
  wyłączeń prądu pod tym adresem,
- **ulubione wydarzenia i przystanki**,
- **ustawienia powiadomień** (które włączone, próg alertu smogowego, godzina
  przypomnienia o wywozie),
- **ukryte komunikaty** (identyfikatory wiadomości, które sam schowałeś),
- **wybrany motyw** (jasny / ciemny / jak system),
- **pobrane dane publiczne** w pamięci podręcznej (wydarzenia, wiadomości, harmonogram
  odpadów, rozkład jazdy), żeby aplikacja działała bez internetu.

Te dane **nie opuszczają urządzenia**. Odinstalowanie aplikacji usuwa je wszystkie.
Wyczyszczenie danych aplikacji w ustawieniach systemu daje ten sam efekt.

## Z czym aplikacja łączy się przez internet

Aplikacja pobiera publicznie dostępne dane. W żadnym z tych połączeń nie wysyła Twojego
adresu, identyfikatora urządzenia ani niczego, co pozwalałoby Cię rozpoznać. Adres
zapisany w aplikacji jest używany wyłącznie na telefonie, do filtrowania pobranych
danych.

| Dokąd | Po co |
|---|---|
| `raw.githubusercontent.com` | wydarzenia, wiadomości, harmonogram odpadów, wyniki sportowe |
| `km.rybnik.pl` | rozkład jazdy KM Rybnik (plik GTFS) |
| `api.gios.gov.pl` | jakość powietrza, stacja Rybnik-Borki (GIOŚ) |
| `www.tauron-dystrybucja.pl` | wyłączenia prądu (publiczne API Tauron Dystrybucja) |

Standardowo, jak przy każdym połączeniu internetowym, serwery te widzą adres IP
Twojego urządzenia. Nie mamy na to wpływu i nie mamy do tych logów dostępu.

## Uprawnienia

- **Internet** — pobieranie danych opisanych wyżej.
- **Powiadomienia** — przypomnienia o wywozie odpadów, ulubionych wydarzeniach,
  przekroczeniu progu smogu, komunikatach miejskich i wyłączeniach prądu. Możesz
  odmówić, a aplikacja działa dalej, tylko bez powiadomień.
- **Uruchamianie po starcie systemu** — odtworzenie zaplanowanych przypomnień po
  restarcie telefonu.

Aplikacja **nie prosi o dostęp** do lokalizacji, kontaktów, aparatu, mikrofonu,
plików ani kalendarza.

## Dzieci

Aplikacja nie jest kierowana do dzieci i nie zbiera danych od nikogo, niezależnie
od wieku.

## Źródła danych i zastrzeżenie

Dane pochodzą od: KM Rybnik, EKO Sp. z o.o., Miasta Rybnik, Głównego Inspektoratu
Ochrony Środowiska, Tauron Dystrybucja, lokalnych redakcji (Radio 90, rybnik.com.pl,
nowiny.pl, tuRybnik), instytucji kultury oraz serwisu 90minut.pl.

**Aplikacja jest projektem niezależnym i nie jest oficjalnym produktem Miasta Rybnik
ani żadnej z wymienionych instytucji.** Dane prezentujemy w dobrej wierze, ale nie
gwarantujemy ich poprawności ani aktualności. W sprawach urzędowych zawsze sprawdzaj
źródło.

## Zmiany

Zmiany w tej polityce będą publikowane pod tym samym adresem, wraz z nową datą
obowiązywania.

## Kontakt

office@admin-stack.com
