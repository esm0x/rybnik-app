# Publikacja w Google Play — krok po kroku

Stan na 26 września 2026. Wszystko, co dało się przygotować z repozytorium, jest już
zrobione: konfiguracja podpisywania, minifikacja, ikona, grafika promocyjna, zrzuty
ekranu, opisy i polityka prywatności. Poniżej to, co musisz zrobić Ty, bo wymaga
Twojego konta, Twojego klucza albo Twojej decyzji.

---

## Zanim zaczniesz: dwie decyzje nie do cofnięcia

### 1. Identyfikator pakietu

Obecnie: **`eu.rybnik.events`**.

Po pierwszej publikacji **nie da się go zmienić** — zmiana oznacza nową, osobną
pozycję w sklepie i utratę wszystkich instalacji i ocen. Dwie rzeczy warte przemyślenia
teraz:

- Człon `events` został z czasów, gdy aplikacja robiła tylko wydarzenia. Dziś robi
  siedem modułów.
- Przestrzeń `eu.rybnik` należy w praktyce do oficjalnej aplikacji miasta
  (halo! RYBNIK, pakiet `eu.rybnik`, wydawca netkoncept.com). Google tego nie
  weryfikuje i nie zablokuje publikacji, ale nasz pakiet wygląda jak jego podgałąź,
  czego nie jesteśmy.

Jeśli chcesz zmienić, zrób to **przed** pierwszym uploadem. To zmiana `applicationId`
i `namespace` w `app/build.gradle.kts` plus przeniesienie katalogów pakietu —
kilkanaście minut roboty. Jeśli zostawiamy jak jest, po prostu idź dalej.

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

```bash
keytool -genkeypair -v -keystore rybnik-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias rybnik
```

Zapyta o hasło i dane właściciela. Zapisz plik `.jks` **i oba hasła** w menedżerze
haseł oraz w drugim, niezależnym miejscu (kopia offline).

Następnie utwórz `keystore.properties` w katalogu głównym projektu (obok
`settings.gradle.kts`):

```properties
storeFile=../rybnik-release.jks
storePassword=TWOJE_HASLO
keyAlias=rybnik
keyPassword=TWOJE_HASLO_KLUCZA
```

Plik jest w `.gitignore`. Gradle sam go wykryje: jeśli istnieje, build release zostanie
podpisany; jeśli nie, powstanie APK bez podpisu i nic się nie wysypie.

Włącz też **Play App Signing** przy pierwszym wydaniu (Google proponuje to sam).
Google przechowuje wtedy klucz właściwy, a Ty podpisujesz kluczem upload — jeśli
zgubisz swój, da się go zresetować. Bez tego nie ma odwrotu.

## Krok 3. Zbuduj AAB

Google Play przyjmuje **Android App Bundle**, nie APK.

```bash
cd "C:/Users/Marcin/Documents/APKA/files/rybnik-app/rybnik-app" && JAVA_HOME="/c/Users/Marcin/.jdks/jbr-21.0.11" ./gradlew bundleRelease
```

Wynik: `app/build/outputs/bundle/release/app-release.aab`.

> `JAVA_HOME` jest tu potrzebne, bo JBR w Android Studio to już JDK 25, którego
> Gradle 8.9 nie obsługuje, a na PATH siedzi Java 8. Z Android Studio zbudujesz przez
> **Build → Generate Signed App Bundle** i nic nie trzeba ustawiać.

Sprawdź, czy AAB jest podpisany:

```bash
"C:/Users/Marcin/AppData/Local/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs app/build/outputs/bundle/release/app-release.aab
```

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
| Grupa odbiorców | **18+** albo „wszyscy dorośli"; aplikacja nie jest kierowana do dzieci |
| Bezpieczeństwo danych | patrz niżej |
| Aplikacja rządowa | **Nie** |
| Zgodność z zasadami | zaznacz oświadczenia |

### Bezpieczeństwo danych — dokładne odpowiedzi

To formularz, w którym najłatwiej o kosztowną pomyłkę, a u nas sprawa jest prosta:

- Czy aplikacja zbiera lub udostępnia wymagane typy danych? → **Nie**
- Czy dane są szyfrowane podczas przesyłania? → **Tak** (całość leci po HTTPS)
- Czy użytkownik może poprosić o usunięcie danych? → **Nie dotyczy** (nie ma serwera;
  odinstalowanie usuwa wszystko lokalnie)

Adres do harmonogramu odpadów **nie jest** „zbieraniem danych" w rozumieniu Google:
nigdy nie opuszcza urządzenia. Gdybyśmy kiedykolwiek wysyłali go na serwer, tę
odpowiedź trzeba będzie zmienić.

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
