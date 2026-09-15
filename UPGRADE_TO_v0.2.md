# Update do v0.2 — realne dane z TZR

Ten pakiet zawiera scraper Pythona, workflow GitHub Actions i zmiany w apce Android, żeby czytała `events.json` generowany przez scraper.

## Co jest w środku

```
scraper/
  scraper.py            ← główny skrypt
  requirements.txt
  README.md
  data/.gitkeep         ← events.json wygeneruje CI

.github/workflows/
  scrape.yml            ← cron co 6h + commit wyniku

app/
  build.gradle.kts                                 ← nowe zależności (OkHttp, kotlinx.serialization)
  src/main/AndroidManifest.xml                     ← android:name=".RybnikApplication"
  src/main/java/eu/rybnik/events/
    RybnikApplication.kt                           ← nowy
    data/RemoteEventRepository.kt                  ← nowy
    ui/events/EventListScreen.kt                   ← nadpisany (loading/error/refresh)
    ui/events/EventDetailScreen.kt                 ← nadpisany (używa Graph.eventRepo)
```

## Krok po kroku

### 1. Rozpakuj do istniejącego projektu

Wypakuj ten ZIP do folderu `rybnik-app/` (tego, który otwierasz w Android Studio). Foldery `scraper/`, `.github/` powstaną na nowo, pliki w `app/` — nadpiszą stare.

Stary `MockEventRepository` możesz zostawić — nie jest już używany, ale nie przeszkadza. Chcesz go usunąć — usuń `app/src/main/java/eu/rybnik/events/data/Event.kt`… nie, wait. `Event.kt` zawiera też model + enum + interface. **Zostaw `Event.kt`, tylko usuń z niego klasę `MockEventRepository` i listę `sampleEvents`.** Wszystko inne jest nadal potrzebne.

### 2. Wrzuć projekt na GitHub

Jeśli jeszcze nie masz repo:
```
cd rybnik-app
git init
git add .
git commit -m "initial commit"
gh repo create rybnik-app --public --source=. --push
```
(albo przez interfejs github.com → New repository → wrzuć ręcznie)

### 3. Odpal workflow ręcznie za pierwszym razem

Na github.com → zakładka **Actions** → workflow "Scrape events" → **Run workflow**. Po ~1 minucie w repo pojawi się `scraper/data/events.json`.

### 4. Wskaż apkę na Twoje repo

W pliku `RemoteEventRepository.kt`, na dole:
```kotlin
const val DEFAULT_URL =
    "https://raw.githubusercontent.com/YOUR_GH_USER/rybnik-app/main/scraper/data/events.json"
```
Podmień `YOUR_GH_USER` na Twój GitHub username. Zsyncuj Gradle w AS, Run.

### 5. Sanity check

- Pierwsze uruchomienie: apka spróbuje pobrać `events.json` z sieci. Jeśli się uda, zobaczysz realne wydarzenia. Jeśli nie (brak sieci albo zły URL) — pusty ekran z bannerem błędu i przyciskiem "Ponów".
- Kolejne uruchomienia: apka pokazuje ostatnio zapisany cache od razu, w tle robi refresh.
- Ikona odświeżenia w prawym górnym rogu wymusza ponowny fetch.

## Znane ograniczenia MVP scrapera

- **Miejsce** zawsze ustawione na "Teatr Ziemi Rybnickiej". Realnie część wydarzeń jest w Fundacji Elektrowni, kościołach itd. — trzeba rozbudować heurystykę parsowania.
- **Wydarzenia wieloterminowe** (np. Zdolni i Skromni: 4 terminy) pokazują tylko pierwszy termin jako `start` i ostatni jako `end` — nie tworzą osobnych wpisów per termin.
- **Jedno źródło**: tylko TZR. Kolejne (RCK, Stara Kotłownia, rybnik.eu) dołożymy jako oddzielne funkcje `parse_*`.

## Kiedy coś nie działa

- **Workflow się nie uruchomił** — Actions muszą być włączone (Settings → Actions → General → Allow all actions).
- **Workflow się uruchomił ale nie ma commita** — otwórz log runa, sprawdź czy `scraper.py` wypisał sensowną liczbę eventów. Jeśli 0, TZR pewnie zmieniło HTML.
- **Apka mówi "HTTP 404"** — URL w `RemoteEventRepository` ma zły username albo repo jest prywatne (musi być publiczne, żeby raw.githubusercontent.com działało bez auth).
- **Apka mówi "Empty response body"** — CI jeszcze się nie zdążył wykonać po pierwszym pushu. Poczekaj minutę, kliknij Refresh.
