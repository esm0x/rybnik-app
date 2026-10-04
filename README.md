# Rybnik — aplikacja miejska

Natywna aplikacja Android dla mieszkańców Rybnika: wydarzenia, harmonogram odpadów,
rozkład jazdy, lokalne wiadomości i jakość powietrza.

## Stan obecny (v1.2)

Wszystkie moduły działają na realnych danych.

| Moduł | Źródło | Skala |
|---|---|---|
| **Start** | agregat pozostałych modułów | dashboard „co dziś ważnego" |
| **Wydarzenia** | TZR, iRybnik, biletyna.pl, 3 domy kultury, ROW + wpisy ręczne | ~131 wydarzeń |
| **Transport** | GTFS z KM Rybnik | 626 przystanków, 44 linie, 71 tys. odjazdów, wyszukiwarka połączeń |
| **Śmieci** | 16 PDF-ów z rybnik.eu (EKO Sp. z o.o.) | 98 rejonów, 880 ulic, 27 dzielnic |
| **Wiadomości** | Radio 90, rybnik.com.pl, rybnik.eu, nowiny.pl, tuRybnik | 120 pozycji, alerty na górze |
| **Powietrze** | GIOŚ, stacja Rybnik-Borki (834) | PM10, PM2,5 + indeks jakości |
| **Wyłączenia prądu** | Tauron (publiczne API `waapi`) | dopasowane do numeru domu |
| **Sport** | 90minut.pl + ekstraliga.pl | 3 kluby, play-offy żużla, linki do relacji |
| **Gdzie wyrzucić** | rybnik.eu + słownik ręczny | 2 PSZOK-i, GPZON, 59 haseł |
| **Ostrzeżenia** | IMGW (publiczne API) | meteo po TERYT 2473, hydro regionalnie |

Powiadomienia: wywóz odpadów (wieczór przed), ulubione wydarzenie (dzień przed),
alert smogowy (próg do ustawienia), komunikaty miejskie oraz wyłączenia prądu
pod zapisanym adresem.

Wiadomości i komunikaty można ukryć pojedynczo — alertów nie kasuje ani wiek, ani
limit 120 pozycji, więc bez tego remont ulicy potrafił wisieć na górze tygodniami.
Ukryte siedzą w DataStore, da się je przejrzeć i przywrócić, i nie wracają jako
powiadomienie.

Motyw: jasny / ciemny / jak system, przełączany w Ustawieniach.
Ekran „Wesprzyj projekt" w zakładce Więcej: buycoffee, link do repo i zgłaszanie błędów.

## Stack

- Kotlin 2.0.21 + Jetpack Compose (Material 3), Navigation Compose
- ViewModel + StateFlow, OkHttp 4.12, kotlinx.serialization 1.7.3
- **Room** — rozkład jazdy (71 tys. odjazdów to za dużo na plik JSON)
- **DataStore** — adres, ulubione, ustawienia powiadomień
- **WorkManager** — przypomnienia
- Scrapery: Python 3.12, `requests` + `beautifulsoup4` + `pdfplumber`
- `minSdk 26`, `targetSdk 35`, AGP 8.7.0, Gradle 8.9

## Układ projektu

```
app/src/main/java/com/adminstack/rybnik/
  RybnikApp.kt                 nawigacja: 5 zakładek + ekrany szczegółowe
  RybnikApplication.kt         object Graph — ręczne DI
  core/
    net/Remote.kt              RemoteConfig (GH_USER!), CachedRemoteSource
    prefs/UserPrefs.kt         DataStore: adres, ulubione, powiadomienia
  data/
    Event.kt, RemoteEventRepository.kt
    waste/                     model + dopasowanie adresu + reguły tygodniowe
    transit/                   Room, import GTFS, odjazdy, planer połączeń skąd-dokąd
    news/, air/
    outages/                   Tauron + parser adresów z testami
    points/                    PSZOK, GPZON, słownik odpadów
    sport/                     mecze 3 klubów + wybór kafla z testami
  ui/
    home/  events/  waste/  transit/  news/  sport/  points/  more/  common/  theme/
    more/SupportScreen.kt      wsparcie projektu + wersja aplikacji
  work/Reminders.kt            WorkManager + kanały powiadomień
  work/ReminderAlarms.kt       alarm dobowy + odbiorniki alarmu i bootu
  widget/WasteWidget.kt        widget: najbliższy wywóz + PM10 (Glance)

scraper/
  CONTRACT.md                  wiążący kontrakt JSON <-> Kotlin
  scraper.py                   wydarzenia (wiele źródeł)
  waste.py                     parser PDF-ów z harmonogramami
  news.py                      RSS + scraping rybnik.eu
  sport.py                     90minut (piłka) + oficjalna strona żużla
  transit.py                   rozwiązuje adres aktualnego GTFS
  data/*.json                  generowane, commitowane przez CI
  data/manual_events.json      JEDYNY plik edytowany ręcznie (kluby z FB)
```

## Uruchamianie

1. Otwórz **ten** folder (ten z `settings.gradle.kts`) w Android Studio.
2. W `app/src/main/java/com/adminstack/rybnik/core/net/Remote.kt` ustaw `GH_USER`
   na swoją nazwę użytkownika GitHub.
3. Gradle sync → Run na urządzeniu z API 26+.

Z terminala: `./gradlew assembleDebug`

## Jak płyną dane

Scrapery działają w GitHub Actions i commitują JSON-y do `scraper/data/`.
Aplikacja pobiera je z `raw.githubusercontent.com` i cachuje lokalnie, więc po
pierwszym uruchomieniu działa offline. Rozkład jazdy jest wyjątkiem: `transit_meta.json`
zawiera tylko adres aktualnego `gtfs.zip`, a apka pobiera i importuje go sama do Room.

Kontrakt pól opisuje `scraper/CONTRACT.md` — **nazwy pól i wartości enumów są wiążące**,
Kotlin deserializuje je 1:1.

## Pułapki w danych (zweryfikowane, obsłużone w kodzie)

- **GTFS**: `calendar.txt` ma wszystkie flagi dni = 0 — kursowanie wynika wyłącznie
  z `calendar_dates.txt`. Naiwny parser zwróci zero odjazdów. `shapes.txt` jest pusty
  (brak geometrii tras). Jedna linia ma `route_short_name` = `-->` mimo 87 realnych kursów.
  ID załącznika z GTFS zmienia się co edycję, więc `transit.py` rozwiązuje je ze strony.
- **Śmieci**: harmonogram zależy od **ulicy, numeru i parzystości**, nie od dzielnicy.
  Zabudowa jedno- i wielorodzinna to dwa różne modele — pierwsza ma konkretne daty,
  druga reguły typu „piątek tydzień nieparzysty". W PDF-ach puste miesiące renderują się
  jako `-`, więc tokeny trzeba wiązać z kolumnami po współrzędnej X, nie dzielić stringa.
- **IMGW: dwa endpointy, dwa różne kształty.** Ostrzeżenia meteo mają `teryt` (lista
  kodów powiatowych), więc da się je dopasować do Rybnika dokładnie — kod **2473**,
  sprawdzony w rejestrze GUS, nie zgadnięty z kodu województwa. Hydrologiczne mają
  zamiast tego `obszary` z kodami zlewni, których bez mapy rzek nie da się sprowadzić do
  miasta, więc filtrujemy je po województwie i oznaczamy w tytule jako regionalne.
  Ostrzeżenia bezterminowe (głównie susze) mają datę końca **9999-12-31** — wypisana
  wprost wygląda jak błąd, więc jest pomijana.
- **PSZOK/GPZON: menu strony też siedzi w `<li>`.** Na obu stronach rybnik.eu nawigacja,
  okruszki, tytuł i godziny otwarcia są elementami listy w tym samym kontenerze co
  prawdziwa lista frakcji. Ani pozycja, ani długość ich nie odróżni: lista przyjmowanych
  odpadów to elementy **po nagłówku „Rodzaje … odpadów"** i na tym kotwiczy się parser.
  Uwaga na filtry długości — „szkło" ma dokładnie 5 znaków i próg `> 5` po cichu je zjadł.
- **Godziny PSZOK-u zostają surowym tekstem.** Miasto pisze je prozą, z literówką
  („piatek"), a normalizacja dokłada precyzję, której w źródle nie ma.
- **Sieć jest gwiaździsta, nie kratowa.** Między Boguszowicami Starymi a Kamieniem nie ma
  **ani jednego** bezpośredniego kursu, a obie dzielnice spotykają się tylko na pięciu
  przystankach, wszystkich w Śródmieściu albo na Północy Karolince. Dlatego wyszukiwarka
  połączeń musi umieć przesiadkę: wersja licząca tylko bezpośrednie odpowiadałaby „brak
  połączenia" dokładnie na pytania, które ludzie zadają. Druga przesiadka jest świadomie
  pominięta, bo z samych Boguszowic Starych 104 przystanki są osiągalne bez zmiany, a
  reszta po jednej.
- **`arrival_time` równa się `departure_time`** we wszystkich 71 116 wierszach `stop_times`,
  więc baza trzyma jedną kolumnę i nic przez to nie traci.
- **GIOŚ**: wysłanie nagłówka `Accept: application/json` powoduje HTTP 406.
  Klucze JSON są polskimi zdaniami. Progi PM10: informowanie 100, alarm 150 µg/m³
  (starsze źródła podają nieaktualne 200/300). Jeden wskaźnik potrafi mieć **kilka
  stanowisk** — Rybnik-Borki ma dwa PM10 (5466 automatyczne, 5467 manualne).
  Stanowiska manualne zwracają HTTP 400 na dane bieżące, bo wyniki są publikowane
  dopiero po 4–8 tygodniach, a odpowiedź API nie mówi, które jest które. Dlatego
  aplikacja próbuje kolejnych id, aż któreś odda pomiar.
- **rybnik.eu**: nie ma RSS-a, tylko HTML. Sekcja „komunikaty" to worek na ogłoszenia,
  nie tablica awarii — o priorytecie ALERT decydują słowa kluczowe.
- **Tauron**: w Polsce jest **pięć** miejscowości „Rybnik" — pierwsza z API to wieś
  w łódzkiem, więc id miasta (GAID 13, śląskie) jest zaszyte na sztywno.
  Endpoint wyłączeń przyjmuje numer domu, ale **go ignoruje** i zwraca cały rejon
  dystrybucyjny, więc filtrowanie musi być po stronie apki. Dla wyłączeń planowanych
  adresy istnieją wyłącznie jako tekst („Budowlanych 78, 76, 74B", „Janasa nieparzyste
  6 do 14"), pisany ręcznie i miejscami błędny — w jednym komunikacie etykiety
  parzystości są odwrotne do numerów. Dlatego parser traktuje parzystość jako
  podpowiedź, która może tylko poszerzyć dopasowanie, i przy niejednoznaczności
  ostrzega zamiast milczeć. Logika ma testy jednostkowe na prawdziwych komunikatach:
  `app/src/test/.../OutageAddressTest.kt`.
- **Czasy z Taurona** przychodzą ze znacznikiem `Z`. Traktujemy je jako UTC i
  przeliczamy na strefę telefonu — warto raz porównać z witryną Taurona, czy nie
  są to jednak godziny lokalne opisane jako UTC.
- **GTFS, linia `-->`**: to nie jest linia pasażerska, tylko 87 kursów technicznych
  („Wyjazd na linię", „JADA NA SZYCHTA", „Dojazd na linię Chłodnie") — puste autobusy
  do zajezdni i zmiany kierowców. Mają jednak 174 wpisy w `stop_times` na realnych
  przystankach, więc bez odfiltrowania pojawiały się w tablicy odjazdów. Import ją
  pomija. **Uwaga: linia `A` to co innego** — prawdziwa linia z 54 kursami przez
  Zamysłów i Smolną, która po prostu nie ma nazwy długiej. Nie wyrzucać.
- **90minut.pl**: jedzie po **HTTP** — port 443 odrzuca połączenie, więc wymuszenie
  HTTPS kończy się `ECONNREFUSED` — i serwuje **ISO-8859-2**. `id_sezon` rośnie o 2 co
  sezon (2021/22 = 99 … 2026/27 = 109), a strona klubu **nie linkuje trwającego sezonu**,
  więc nie da się go wyskrobać stamtąd: numer jest liczony i weryfikowany datami meczów,
  z sąsiednimi id jako zapasem. Wynik bywa `0-3 (wo)` albo `0-0k. 6-7` — gołe `\d+-\d+`
  to za mało. Wiosenne kolejki mają datę bez godziny, więc `time` jest nullowalne,
  a nie udawane jako 00:00.
- **Certum Trusted Root CA**: `api.gios.gov.pl`, `km.rybnik.pl` i
  `www.tauron-dystrybucja.pl` kończą łańcuch na tym roocie, którego **nie ma w starszych
  Androidach** — sprawdzone: obraz API 33 wozi `Certum Trusted Network CA` i `Network CA 2`,
  ale nie ten, a API 37 już tak. Efekt: na starszym telefonie rozkład jazdy nie pobierał się
  wcale, a powietrze i wyłączenia prądu cicho pustoszały, podczas gdy ten sam build działał
  na nowszym. Root jedzie więc z apką (`res/raw/certum_trusted_root.pem` +
  `network_security_config.xml`), ograniczony do tych trzech domen. Wysyłanie roota w
  łańcuchu, co robi km.rybnik.pl, nic nie daje: niezaufany root jest ignorowany.
- **rybnik.com.pl**: potrafi zwrócić 403 w GitHub Actions, serwując ten sam adres
  bez problemu z łącza domowego. Blokada jest na zakresie IP centrów danych, nie na
  User-Agencie (sprawdzone: bot UA dostaje 200 z adresu domowego). Scraper ponawia
  próbę trzy razy, a przy twardej blokadzie po prostu odnotowuje błąd w `failures` —
  pozostałe trzy źródła lecą dalej.

## Znane ograniczenia

- Rozkład jazdy nie ma geometrii tras (brak `shapes.txt`), więc „trasa linii" to
  lista przystanków najdłuższego kursu, nie linia na mapie. Mapy nie ma w ogóle.
- **Brak realnego czasu rzeczywistego.** KM Rybnik nie publikuje GTFS-Realtime.
  Istnieje nieudokumentowane API `rozklad.km.rybnik.pl/Home/GetNextDepartues`, ale
  sprawdzone: jego `vr` to dokładnie różnica między czasem serwera a rozkładowym
  (przy czasie 20:10 i odjazdzie 20:49 zwraca 2336 s), czyli odliczanie z rozkładu
  bez GPS. Endpoint pojazdów zwraca pustkę. Apka odlicza więc lokalnie z GTFS i
  odświeża listę co 20 s — bez opóźnień w czasie rzeczywistym, bo takich danych nie ma.
- Trzy wpisy adresowe z PDF-ów gubią wyjątki („poza numerem 205D") — zakres
  wychodzi minimalnie za szeroki. Logowane jako `[warn]` przy generowaniu.
- `Impreza` ma mało wydarzeń poza sezonem klubowym — klasyfikator działa,
  ale we wrześniu w źródłach po prostu nie ma imprez.
- DK Chwałowice i DK Niedobczyce nie mają eksportu iCal, więc ich parsery są kruche.
- **Kluby Noc i Szepty nie mają żadnego źródła maszynowego.** Sprawdzone: Going,
  ebilet, biletyna, kupbilecik, kicket, koncertomania — wszędzie strona miejsca
  istnieje, ale z zerem wydarzeń; `klubnoc.com` to SPA bez danych, Szepty nie mają
  strony, iRybnik ich nie listuje, mostki Instagram→RSS są za Cloudflare.
  Publikują tylko na Facebooku, a API wydarzeń stron Meta nie istnieje od 2018 —
  scrapowanie łamałoby regulamin i i tak psułoby się co chwilę. Dlatego wchodzą
  przez `scraper/data/manual_events.json`.
- **Żużel: play-offy są tylko u organizatora ligi.** Strona klubu publikuje wyłącznie
  terminarz „rundy zasadniczej" i kończy się na 14. rundzie, więc półfinał z PSŻ Poznań
  (23.08 i 06.09.2026) w ogóle nie trafiał do apki — testerzy odczytali to jako brak
  oznaczenia play-offu. Źródłem jest teraz ekstraliga.pl, gdzie każdy mecz ma podtyp
  (`match_subtype`: część zasadnicza, play-off, play-down, baraże) i własną stronę
  z oficjalnymi wynikami i relacją. Strona klubu została jako awaryjna.
  - Dane nie leżą w HTML-u, tylko w strumieniu React Server Components: zescapowany JSON
    pocięty na kawałki `self.__next_f.push`.
  - `datetime_schedule` to chwila UTC w milisekundach. Odczytana naiwnie wychodzi dobrze
    na polskim laptopie i **dwie godziny za wcześnie na GitHub Actions**, które chodzą
    w UTC — stąd jawne przeliczenie na `Europe/Warsaw` i `tzdata` w zależnościach.
  - Mecz potrafi mieć wynik w trakcie jazdy (status „W trakcie"), więc za końcowy
    uchodzi wyłącznie przy statusie „Rozegrany", nigdy po samej obecności liczb.
  - Dwumecze dostają etykiety „mecz 1" i „rewanż", a rewanż sumę („dwumecz 87:93"),
    bo sam wynik rewanżu nie mówi, kto przeszedł dalej.
- **Linki do relacji są nierówne, bo źródła są nierówne.** Żużel ma stronę każdego meczu.
  Piłka mężczyzn ma ją na 90minut dla meczów ligowych — z opóźnieniem po meczu i nigdy
  dla regionalnego Pucharu (POkr). **Piłka kobiet nie ma jej wcale**: 90minut nie
  zakłada stron meczów dla III ligi kobiet, nawet za cały rozegrany sezon. Wynik bez
  linku nie udaje klikalnego. 90minut działa tylko po HTTP — link otwiera przeglądarka,
  nie klient apki, więc zakaz ruchu jawnego go nie blokuje, ale Chrome pokaże przy
  adresie znak „niezabezpieczona".
- **SQLite ma sufit na liczbę zmiennych w zapytaniu** i Room rozwija `IN (:ids)` na jeden
  parametr na element. Limit to 999 na Androidzie 8, więc szeroki dobór przystanków go
  przebijał: wpisanie „Rybnik" jako celu dopasowuje **każdy** przystanek w mieście, bo
  każda nazwa się od tego zaczyna. Zapytanie padało z `too many SQL variables`, wyjątek
  był połykany wyżej, a ekran spokojnie meldował „Brak połączeń". Listy identyfikatorów
  idą teraz porcjami, a nieudane wyszukiwanie mówi, że się nie udało.
- **Nieczytelny numer domu pasuje do każdej reguły.** `StreetDto.matches` w razie
  wątpliwości zwraca `true`, więc „abc" trafiało do pierwszego rejonu z listy i dostawało
  pewnie wyglądający, cudzy harmonogram. Numer jest teraz walidowany przed zapisem.
- **Rozkład jazdy musi się sam odświeżać.** KM Rybnik wydaje feed co kilka miesięcy
  z nowym `attachment_id`, a każde wydanie ma `valid_to`. Import „tylko gdy baza pusta"
  oznaczał, że apka serwowała pierwszą pobraną edycję **na zawsze**, a od dnia, w którym
  jej kalendarz się kończył, każdy przystanek o dziewiątej rano mówił „dziś nic już nie
  odjeżdża". Dlatego edycja w bazie jest zapisywana i porównywana ze świeżym
  `transit_meta.json`, a przeterminowanie jest pokazywane, nie chowane.
- **Nieudany import nie może ukryć działającego rozkładu.** Importer czyści bazę dopiero
  po udanym pobraniu i sparsowaniu, ale gałąź błędu budowała świeży `TransitStatus`,
  w którym `ready` wracało do `false` — ekran ogłaszał „rozkład nie jest jeszcze
  wczytany", siedząc na sprawnych danych.
- **Przypomnienia stoją na dwóch mechanizmach, bo psują się inaczej.** Sam
  `PeriodicWorkRequest` nie wystarczał: sześć godzin to *minimum* plus okno elastyczne,
  a w Doze system dokłada swoje, więc wieczorne przypomnienie o wywozie potrafiło
  przyjechać o trzeciej w nocy albo po wywozie. Dlatego termin niesie `AlarmManager`
  (`setAndAllowWhileIdle`), a worker okresowy został jako siatka bezpieczeństwa dla
  rzeczy bez deadline'u: smogu, komunikatów i wyłączeń prądu. Świadomie **nie**
  `setExactAndAllowWhileIdle`: wymaga `SCHEDULE_EXACT_ALARM`, które od Androida 14 jest
  domyślnie odmawiane, a Google Play daje je budzikom i kalendarzom. Wystawienie kubłów
  budzikiem nie jest.
- **Alarmy nie przeżywają restartu**, w przeciwieństwie do zadań WorkManagera, dlatego
  doszedł `BootReceiver` na `BOOT_COMPLETED` i `MY_PACKAGE_REPLACED`.
- **Powiadomienia o komunikatach gubiły większość komunikatów.** Pierwsza wersja pytała
  tylko „czy najnowszy komunikat jest z dzisiaj?". Z historii `news.json` w gicie: od
  20.09 do 04.10 pojawiło się 17 nowych komunikatów, z czego 4 trafiły do danych dopiero
  następnego dnia (więc „dzisiaj" odpadało na zawsze), a 6 przegrało z rodzeństwem w dni
  z kilkoma, bo brany był tylko najnowszy. Do tego stały identyfikator powiadomienia kazał
  kolejnym nadpisywać poprzednie. Teraz (`work/NotificationRules.kt`, z testami):
  - każdy komunikat ma własne powiadomienie, kilka naraz układa się w grupę z nagłówkiem
    „N nowych komunikatów" (odmiana sprawdzona aż po 12-14 i 22);
  - liczy się, że jeszcze nie powiedzieliśmy, nie dzień publikacji — z limitem 72 h,
    żeby pierwsze uruchomienie po aktualizacji nie wysypało tygodnia zaległości;
  - komunikat, który był na ekranie apki co najmniej 1,5 s, liczy się jako przeczytany
    i nie przychodzi jako powiadomienie;
  - każda rzecz jest ogłaszana raz: pamięć wysłanych (datowane klucze w DataStore,
    czyszczone po 14 dniach) obejmuje też wywóz, wydarzenia, smog i wyłączenia prądu.
    Bez niej od wersji 1.2 alarm o 18:00 i zadanie okresowe zapowiadały te same kubły
    dwa razy, a smog przy zadaniu co 3 h dzwoniłby osiem razy na dobę.
- **„Przeczytane" musi znaczyć „na wierzchu".** Kompozycja przeżywa zejście aktywności
  w tło, więc pierwsza wersja liczyła dalej przy launcherze na ekranie: karuzela na
  Starcie kręciła się niewidoczna i w minutę oznaczyła wszystkie 9 aktualnych komunikatów
  jako przeczytane, przez co worker nie miał czego wysłać. Oznaczanie i karuzela działają
  teraz tylko w stanie `RESUMED` (`repeatOnLifecycle`), sprawdzone na emulatorze: licznik
  stoi przez 40 s z launcherem na wierzchu.
- **Alarm i zadanie okresowe potrafią odpalić workera równocześnie**, a oba czytałyby
  pamięć wysłanych, zanim którykolwiek ją zapisze. Worker jest więc pod `Mutex`.
- **Force-stopa nie da się obejść kodem.** Menedżery baterii Xiaomi, Samsunga, Huaweia
  i OPPO kasują wszystkie zaplanowane alarmy i zadania, a zamknięcie apki z listy
  ostatnich robi to samo. Jedyne, co aplikacja może zrobić, to powiedzieć o tym wprost:
  Ustawienia mają sekcję „Działanie w tle", która sprawdza
  `isIgnoringBatteryOptimizations` i prowadzi do systemowego ekranu. Celowo **nie**
  prosimy o `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Play przyznaje je tylko aplikacjom,
  których podstawowa funkcja tego wymaga.
- **Going API ignoruje parametr `place`** — `events?place=999999` zwraca te same
  24 pozycje co dla realnych klubów, w dodatku warszawskie. Nie nadaje się do
  filtrowania po miejscu i celowo nie jest podpięte.
- **Radio 90 jest regionalne** (6 miast), więc czytamy feed tagu `rybnik`, nie
  główny. `/category/rybnik/feed` odpowiada 200, ale zwraca zero pozycji.
- Brak benzo(a)pirenu, mimo że to obok PM10 główny problem Rybnika. Mierzy się go
  wyłącznie manualnie (analiza laboratoryjna filtrów), więc wartości „na teraz"
  nie istnieją — GIOŚ udostępnia je po tygodniach przez API danych archiwalnych.

## Roadmapa

1. ✅ v0.1 — szkielet, mock dane
2. ✅ v0.2 — realne wydarzenia z TZR
3. ✅ v0.3 — wszystkie moduły na realnych danych + powiadomienia + smog
4. ✅ v0.4 — wyłączenia prądu wg adresu, ciemny motyw, Radio 90, ekran wsparcia
5. ✅ v0.5 — sport (ROW 1964, żużel, piłka kobiet), ukrywanie komunikatów,
   przygotowanie do Google Play (patrz `play/`)
6. ✅ v1.0 — wyszukiwarka połączeń skąd-dokąd z jedną przesiadką
7. ✅ v1.1 — widget na pulpit, PSZOK/GPZON + „gdzie wyrzucić X", ostrzeżenia IMGW
8. ✅ v1.2 — przypomnienia odporne na Doze (alarm dobowy), przetrwanie restartu,
   sekcja o oszczędzaniu baterii; play-offy żużla i linki do relacji meczów;
   każdy komunikat z osobnym powiadomieniem, bez duplikatów i bez przeczytanych
9. ⏭️ Mapa przystanków i tras (OSM, bo GTFS nie ma geometrii)
10. ❌ Odjazdy na żywo — odrzucone: KM Rybnik nie ma danych GPS (patrz ograniczenia)
11. ✅ Ciemny motyw + przełącznik jasny / ciemny / jak system
12. ⏭️ Zgłaszanie usterek do miasta (wymaga backendu)
13. ❌ Apteki dyżurne — odrzucone: Rybnik nie publikuje grafiku dyżurów,
    a w mieście nie ma apteki całodobowej

## Notatki projektowe

- Paleta celowo nie jest domyślnym Material 3: głęboki petrol z ciepłym amber.
  Dynamic color jest wyłączony, żeby apka wyglądała tak samo na każdym telefonie.
- Kolory kategorii i typów odpadów siedzą na enumach, więc dodanie wartości to
  zmiana w jednej linii, a kolor propaguje się na filtry, kafelki i kropki w kalendarzu.
- Pakiet to `com.adminstack.rybnik`, przemianowany przed publikacją z `eu.rybnik.events`.
  Krótkie `eu.rybnik` odpadło, bo należy do oficjalnego halo! RYBNIK, a identyfikatory
  są globalnie unikalne. Prefiks firmowy skaluje się na kolejne miasta.
