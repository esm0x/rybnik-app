# Publikacja w Google Play — krok po kroku

Stan na 26 września 2026. Wszystko, co dało się przygotować z repozytorium, jest już
zrobione: konfiguracja podpisywania, minifikacja, ikona, grafika promocyjna, zrzuty
ekranu, opisy i polityka prywatności. Poniżej to, co musisz zrobić Ty, bo wymaga
Twojego konta, Twojego klucza albo Twojej decyzji.

> **Komendy są napisane pod `cmd.exe`**, bo w nim pracujesz. Dwie rzeczy, które na tej
> maszynie zaskakują:
>
> * `keytool`, `jarsigner` i `java` **nie są na PATH** — jest tam tylko Java 8, która
>   i tak nie wystarczy. Dlatego niżej wszędzie są pełne ścieżki do JDK 21.
> * Zmienna `NoDefaultCurrentDirectoryInExePath=1` sprawia, że cmd **nie szuka programów
>   w bieżącym katalogu**, więc samo `gradlew.bat` kończy się „is not recognized".
>   Musi być `.\gradlew.bat`.
>
> W Git Bashu te same komendy wyglądają inaczej (`export JAVA_HOME=...`, `./gradlew`).
> Ścieżki w cudzysłowie z ukośnikami w przód działają w obu powłokach.

---

## Zanim zaczniesz: dwie decyzje nie do cofnięcia

### 1. Identyfikator pakietu — ustalony

**`com.adminstack.rybnik`**. Po pierwszej publikacji nie da się go zmienić: zmiana
oznaczałaby nową, osobną pozycję w sklepie i utratę instalacji oraz ocen.

Rozważaliśmy `eu.rybnik` i **odpada**: ten pakiet należy do oficjalnego halo! RYBNIK
(wydawca netkoncept.com), a identyfikatory są globalnie unikalne, więc Google po prostu
odrzuciłby upload. Poprzednie `eu.rybnik.events` działałoby, ale wyglądało jak podgałąź
pakietu miasta, czym nie jesteśmy, a człon `events` został z czasów, gdy aplikacja
robiła tylko wydarzenia.

Prefiks firmowy skaluje się też na kolejne miasta: `com.adminstack.zory`,
`com.adminstack.gliwice`.

### 2. Nazwa i ryzyko „podszywania się"

Aplikacja nazywa się **Mój Rybnik** i wygląda jak aplikacja miejska, ale nią nie jest.
Google potrafi odrzucić listing, który sugeruje związek z instytucją publiczną.
Zabezpieczenie jest już w trzech miejscach: w opisie sklepu, w polityce prywatności
i na ekranie „Wesprzyj projekt" w samej aplikacji. **Nie usuwaj tych zdań** — to one
odróżniają „niezależny projekt" od „podszywania się pod urząd".

---

## Krok 1. Konto Google Play Console

1. Wejdź na https://play.google.com/console i załóż konto dewelopera.
2. Opłata: **25 USD, jednorazowo**, na zawsze.
3. Wybierz typ konta:
   - **Osobiste** — weryfikacja tożsamości dokumentem.
   - **Organizacja** — wymaga numeru **D-U-N-S** (bezpłatny, ale nadawany przez
     Dun & Bradstreet i potrafi zająć do dwóch tygodni). Jeśli publikujesz jako
     admin-stack, załatw D-U-N-S zawczasu, bo to najdłuższy element całej układanki.
4. Konta osobiste założone po listopadzie 2023 mają dodatkowy wymóg: **12 testerów
   przez 14 dni ciągłego testowania zamkniętego**, zanim wolno przejść na produkcję.
   Konta firmowe tego nie mają. To zwykle decyduje o wyborze typu konta.

## Krok 2. Klucz podpisujący

Klucz jest tożsamością aplikacji. **Zgubienie go = brak możliwości wydania
aktualizacji.** Nigdy nie trafia do repozytorium — `.gitignore` już go blokuje.

Generujemy go **poza repozytorium**, w folderze nadrzędnym. Dzięki temu żaden `git add`
nie ma jak go wciągnąć, nawet przez pomyłkę:

```bash
"C:/Users/Marcin/.jdks/jbr-21.0.11/bin/keytool.exe" -genkeypair -v -keystore "C:/Users/Marcin/Documents/APKA/files/rybnik-app/rybnik-release.jks" -keyalg RSA -keysize 4096 -validity 10000 -alias rybnik
```

Zapyta o hasło i dane właściciela. Zapisz plik `.jks` **i hasło** w menedżerze haseł
oraz w drugim, niezależnym miejscu (kopia offline).

> **Hasło klucza musi być identyczne z hasłem magazynu.** `keytool` tworzy dziś magazyny
> w formacie **PKCS12**, a ten nie obsługuje osobnych haseł: jeśli przy pytaniu „Enter
> key password (RETURN if same as keystore password)" podasz inne, zostanie **po cichu
> zignorowane**. Klucz i tak dostanie hasło magazynu, a `keytool` nawet o tym nie
> wspomni przy tworzeniu — powie dopiero przy imporcie:
> `Different store and key passwords not supported for PKCS12 KeyStores`.
>
> Gradle żadnego fallbacku nie ma, więc wpisanie w `keystore.properties` tego drugiego,
> zignorowanego hasła kończy build komunikatem, który niczego nie sugeruje:
>
> ```
> Failed to read key rybnik from store "...": Get Key failed:
> Given final block not properly padded.
> ```
>
> To znaczy po prostu: **złe hasło klucza**. Wpisz w `keyPassword` to samo, co
> w `storePassword`.
>
> Uwaga przy diagnozie: `keytool` przy odczycie klucza sam podstawia hasło magazynu,
> więc `keytool -list` czy `-importkeystore` „przejdą" nawet z zupełnie błędnym
> `-keypass`. Nie da się nimi potwierdzić, że hasło klucza jest poprawne.

Następnie utwórz `keystore.properties` w katalogu głównym projektu (obok
`settings.gradle.kts`):

```properties
storeFile=C:/Users/Marcin/Documents/APKA/files/rybnik-app/rybnik-release.jks
storePassword=TWOJE_HASLO
keyAlias=rybnik
keyPassword=TWOJE_HASLO
```

Ścieżka bezwzględna, z ukośnikami w przód, i dokładnie ta sama co w `keytool` wyżej.
Ścieżka względna też zadziała, ale liczy się od katalogu z `settings.gradle.kts`, nie od
tego, w którym akurat stoisz — a to jest dokładnie ten rodzaj szczegółu, który wychodzi
dopiero przy pierwszym buildzie.

Oba hasła są tu **celowo takie same** — patrz ramka wyżej o PKCS12. Muszą się zgadzać
z tym, co podałeś w `keytool`; jeśli plik powstał z szablonu, podmień `TWOJE_HASLO` na
prawdziwe, inaczej Gradle zgłosi błędne hasło.

**Dopóki plik `.jks` nie istnieje, a `keystore.properties` już tak, każdy
`bundleRelease` kończy się:**

```
Keystore file '...rybnik-release.jks' not found for signing config 'release'
```

To nie jest awaria konfiguracji, tylko informacja, że brakuje jeszcze samego klucza.

Plik jest w `.gitignore`. Gradle sam go wykryje: jeśli istnieje, build release zostanie
podpisany; jeśli nie, powstanie APK bez podpisu i nic się nie wysypie.

Włącz też **Play App Signing** przy pierwszym wydaniu (Google proponuje to sam).
Google przechowuje wtedy klucz właściwy, a Ty podpisujesz kluczem upload — jeśli
zgubisz swój, da się go zresetować. Bez tego nie ma odwrotu.

## Krok 3. Zbuduj AAB

Google Play przyjmuje **Android App Bundle**, nie APK.

```bash
cd /d C:\Users\Marcin\Documents\APKA\files\rybnik-app\rybnik-app
```

```bash
set JAVA_HOME=C:\Users\Marcin\.jdks\jbr-21.0.11
```

```bash
.\gradlew.bat bundleRelease
```

Wynik: `app/build/outputs/bundle/release/app-release.aab`.

> `JAVA_HOME` jest tu potrzebne, bo JBR w Android Studio to już JDK 25, którego
> Gradle 8.9 nie obsługuje, a na PATH siedzi Java 8. `set` działa tylko w tym jednym
> oknie konsoli, więc po jego zamknięciu trzeba je powtórzyć. Z Android Studio zbudujesz przez
> **Build → Generate Signed App Bundle** i nic nie trzeba ustawiać.

Sprawdź, czy AAB jest podpisany. **Nie używaj do tego `apksigner`** — to narzędzie
obsługuje wyłącznie APK i na bundlu wywala się z `ApkFormatException: Missing
AndroidManifest.xml`, bo w AAB manifest leży pod `base/manifest/AndroidManifest.xml`,
a nie w korzeniu archiwum. Bundle podpisuje się schematem JAR, więc sprawdza go
`jarsigner` z JDK:

```bash
"C:/Users/Marcin/.jdks/jbr-21.0.11/bin/jarsigner.exe" -verify -verbose:summary -certs app/build/outputs/bundle/release/app-release.aab
```

Czego się spodziewać:

- podpisany: `jar verified.` plus linia `Signed by "CN=..."` z Twoim kluczem, więc od
  razu widać, **którym** kluczem,
- niepodpisany: `no manifest.` i `jar is unsigned.` — to znaczy, że nie ma `keystore.properties` albo Gradle
  go nie znalazł.

Ostrzeżenia o certyfikacie self-signed i braku znacznika czasu są normalne i nie
przeszkadzają w wysyłce: Play i tak podpisuje wydanie własnym kluczem.

## Krok 4. Utwórz aplikację w Play Console

**Wszystkie aplikacje → Utwórz aplikację**:

- Nazwa: `Mój Rybnik`
- Domyślny język: `polski (Polska) – pl-PL`
- Typ: **Aplikacja**
- Bezpłatna: **tak** *(z darmowej na płatną nie da się przejść później)*

## Krok 5. Wypełnij „Ustawienia aplikacji"

Play Console prowadzi przez listę zadań. Do wypełnienia:

| Sekcja | Co wpisać |
|---|---|
| Polityka prywatności | URL z kroku 7 |
| Reklamy | **Nie zawiera reklam** |
| Dostęp do aplikacji | **Cała zawartość dostępna bez ograniczeń** (nie ma logowania) |
| Ocena treści | Ankieta → kategoria „Wiadomości / informacje", wszędzie „nie" (brak przemocy, hazardu, zakupów). Wyjdzie PEGI 3 |
| Grupa odbiorców | **18+**. Patrz ostrzeżenie niżej, to nie jest drobiazg |
| Bezpieczeństwo danych | patrz niżej |
| Aplikacja rządowa | **Nie** |
| Zgodność z zasadami | zaznacz oświadczenia |

### Grupa odbiorców: nie zaznaczaj dzieci

Wskazanie przedziału wiekowego obejmującego dzieci włącza **Google Play Families
Policy**, a ta wymaga, by **cała** treść w aplikacji była odpowiednia dla dzieci.
Tej gwarancji nie da się tu dać: moduł wiadomości pokazuje na żywo lokalne feedy,
których nie kuratorujemy. W próbce 120 pozycji 9 dotyczyło wypadków i przestępstw,
w tym „19-latka zginęła w Rudach" i statystyki policyjne z ofiarami. Treść zmienia się
codziennie, więc nie da się jej z góry przejrzeć.

Ustaw **18+** w sekcji „Zawartość aplikacji → Grupa odbiorców i treści". Nie ogranicza
to, kto może pobrać aplikację — deklaruje, do kogo jest kierowana. Wtedy znika też
pytanie o plakietkę Families w formularzu Bezpieczeństwa danych.

### Bezpieczeństwo danych — dokładne odpowiedzi

To formularz, w którym najłatwiej o kosztowną pomyłkę, a u nas sprawa jest prosta:

- Czy aplikacja **zbiera** dane? → **Nie.** Nie mamy serwera, nic do nas nie trafia.
- Czy aplikacja **udostępnia** dane? → **Tak, jeden typ: „Adres" (Personal info).**
  Funkcja wyłączeń prądu wysyła ulicę i numer domu do Tauron Dystrybucja, bo bez tego
  nie da się ustalić wyłączeń (sprawdzone: zapytanie bez numeru zwraca zero wyników).
  - cel: **Funkcjonalność aplikacji**
  - czy wymagane: **opcjonalne** (tylko gdy użytkownik zapisze adres)
- Czy dane są szyfrowane podczas przesyłania? → **Tak** (całość leci po HTTPS)
- Czy użytkownik może poprosić o usunięcie danych? → **Nie dotyczy** (nie ma serwera;
  odinstalowanie usuwa wszystko lokalnie)

Harmonogram odpadów **nie** jest tu wliczony: pobieramy komplet rejonów i dopasowanie
do adresu robimy lokalnie, więc ten adres nie opuszcza telefonu.

> Google dopuszcza wyjątek dla danych przekazywanych stronie trzeciej **w wyniku
> świadomego działania użytkownika**, i sprawdzanie wyłączeń pod własnym adresem się
> w to wpisuje. Mimo to rekomendacja jest: **zadeklaruj**. Nadmiarowa deklaracja nie
> jest karana, a rozbieżność między formularzem a rzeczywistością owszem — i to jest
> jedna z najczęstszych przyczyn zawieszenia aplikacji.

## Krok 6. Listing sklepu

Skopiuj gotowe teksty z [`store-listing.md`](store-listing.md) i wgraj:

- ikonę `graphics/icon-512.png`,
- grafikę promocyjną `graphics/feature-graphic-1024x500.png`,
- 8 zrzutów z `screenshots/` w kolejności podanej w tamtym pliku.

## Krok 7. Polityka prywatności pod publicznym adresem

Google wymaga **działającego URL-a**. Plik `PRIVACY.md` jest już w repozytorium,
więc najszybciej:

**Wariant A (30 sekund)** — wskaż plik na GitHubie:
`https://github.com/esm0x/rybnik-app/blob/main/PRIVACY.md`

**Wariant B (ładniej)** — włącz GitHub Pages: *Settings → Pages → Source: main, katalog
`/root`*, i podaj `https://esm0x.github.io/rybnik-app/PRIVACY`.

Wariant A wystarcza do akceptacji.

## Krok 8. Testowanie wewnętrzne

Nie idź od razu na produkcję.

1. **Testowanie → Testowanie wewnętrzne → Utwórz nową wersję**.
2. Wgraj `app-release.aab`, wklej notatki z `store-listing.md`.
3. Dodaj siebie i kilka osób jako testerów (lista adresów e-mail).
4. Zainstaluj z linku testera **na prawdziwym telefonie** i sprawdź w szczególności:
   - pobieranie rozkładu jazdy (największy transfer, najdłuższa operacja),
   - jakość powietrza i wyłączenia prądu — to one padały na starszym Androidzie,
     zanim doszedł certyfikat Certum,
   - powiadomienia po restarcie telefonu.

Wersja release jest zminifikowana przez R8, więc to pierwszy moment, w którym wyszłyby
błędy nieobecne w buildzie debug. Przetestowano już na emulatorze (Android 13) —
deserializacja przeżyła, ale prawdziwy sprzęt to prawdziwy sprzęt.

## Krok 9. Produkcja

1. **Produkcja → Utwórz nową wersję**, ten sam AAB.
2. Kraje: Polska wystarczy; można dać wszystkie.
3. Wyślij do sprawdzenia.
4. Pierwsza weryfikacja trwa zwykle **od kilku dni do dwóch tygodni**, kolejne
   aktualizacje zwykle godziny.

## Krok 10. Kolejne wydania

Przy każdym wydaniu podnieś w `app/build.gradle.kts`:

```kotlin
versionCode = 6        // ZAWSZE +1, Google odrzuci powtórzony numer
versionName = "0.6.0"  // to widzi użytkownik
```

---

## Czego jeszcze nie ma, a warto rozważyć przed produkcją

- **Test na starym Androidzie.** `minSdk` to API 26 (Android 8), a testowaliśmy na
  API 33 i 37. Akurat naprawiony błąd z certyfikatem Certum dotyczy właśnie starszych
  wydań, więc jeden przebieg na obrazie API 26–28 byłby wart zachodu. W SDK nie ma
  `cmdline-tools`, więc obraz trzeba doinstalować z Android Studio:
  *Tools → SDK Manager → SDK Platforms → zaznacz „Show package details" → Android 8.0,
  obraz systemowy x86_64*.
- **Konto testowe dla recenzenta** nie jest potrzebne: aplikacja nie ma logowania.
