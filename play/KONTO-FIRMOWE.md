# Konto firmowe w Google Play Console — jak je założyć

Stan na 26 września 2026. Dotyczy konta typu **Organizacja**, bo plan zakłada więcej
aplikacji miejskich niż jedna.

---

## Dlaczego firmowe, a nie osobiste

| | Osobiste | Organizacja |
|---|---|---|
| Weryfikacja | dokument tożsamości | **numer D-U-N-S** + dokumenty firmy |
| Czas założenia | kilka dni | **2–4 tygodnie** (D-U-N-S to wąskie gardło) |
| 12 testerów przez 14 dni przed produkcją | **wymagane** dla kont założonych po 13.11.2023 | **nie dotyczy** |
| Nazwa wydawcy w sklepie | imię i nazwisko | nazwa firmy |
| Przeniesienie aplikacji później | możliwe, ale upierdliwe | — |

Dla wielu aplikacji dla różnych miast konto firmowe jest jedynym sensownym wyborem:
samorząd, który zobaczy w sklepie wydawcę „Marcin M." zamiast firmy, potraktuje to
inaczej. Do tego odpada wymóg 12 testerów przy każdym nowym mieście.

---

## Krok 1. Numer D-U-N-S (zacznij od tego)

To dziewięciocyfrowy identyfikator firmy nadawany przez Dun & Bradstreet. Google go
wymaga i sam nie nadaje.

1. Sprawdź, czy firma już go ma — bywa nadany automatycznie:
   https://www.dnb.com/duns-number/lookup.html
   Szukaj po nazwie i adresie; wpisy bywają z literówkami, więc próbuj wariantów.
2. Jeśli nie ma, złóż wniosek: https://support.dnb.com/?CUST=GOOGLEDEV
   To dedykowana ścieżka dla deweloperów Google, **bezpłatna**.
3. Przygotuj: pełną nazwę prawną, adres siedziby, NIP/KRS lub wpis do CEIDG, telefon
   firmowy, stronę WWW, imię i nazwisko osoby kontaktowej.
4. Czas: deklarowane **do 30 dni roboczych**, w praktyce zwykle 5–15 dni. Ścieżka
   Google bywa szybsza niż zwykła.

> Dane, które podasz D&B, **muszą się zgadzać co do znaku** z tym, co wpiszesz potem
> w Play Console. Inna forma prawna („sp. z o.o." vs „Sp. z o.o.") albo skrócona ulica
> potrafią wywrócić weryfikację i cofnąć Cię na początek.

## Krok 2. Konto Google dla firmy

Nie używaj prywatnej skrzynki. Załóż lub wskaż konto firmowe, najlepiej
`play@admin-stack.com` albo podobny alias, i **włącz weryfikację dwuetapową**. To konto
staje się właścicielem wszystkich aplikacji — utrata dostępu to utrata portfela apek.

## Krok 3. Rejestracja w Play Console

1. https://play.google.com/console/signup
2. Wybierz **Organizacja / firma**.
3. Opłata **25 USD** jednorazowo, kartą.
4. Wypełnij:
   - nazwa prawna firmy (dokładnie jak w D&B),
   - numer D-U-N-S,
   - adres siedziby,
   - telefon i e-mail kontaktowy (Google je weryfikuje kodem),
   - strona WWW,
   - **nazwa wydawcy** — to widzą użytkownicy w sklepie pod tytułem aplikacji.

## Krok 4. Weryfikacja

Google sprawdza zgodność danych z rejestrem D&B, a czasem prosi o dokumenty
rejestrowe firmy. Etapy widać w Play Console w sekcji **Account details**.

Typowy czas: **2–7 dni** po podaniu poprawnego D-U-N-S. Gdy weryfikacja stoi kilka dni
bez ruchu, napisz przez formularz pomocy w Console — bywa, że czeka na jedno pole.

## Krok 5. Dane do wypłat (nawet przy darmowych apkach)

Google i tak poprosi o profil płatności. Przygotuj NIP i numer konta. Przy aplikacjach
bezpłatnych nic z tego nie wynika, ale bez uzupełnienia część sekcji Console zostaje
zablokowana.

## Krok 6. Zanim ruszą kolejne miasta

Dwie decyzje warte podjęcia raz, na początku, zamiast prostowania przy trzeciej apce:

1. **Schemat identyfikatorów pakietu.** Jeden prefiks firmowy, miasto w kolejnym
   członie, na przykład `com.adminstack.rybnik`, `com.adminstack.zory`. Nigdy nie
   używaj przestrzeni należącej do miasta ani do cudzej aplikacji — przy Rybniku
   `eu.rybnik` okazało się zajęte przez oficjalne halo! RYBNIK, a identyfikatory są
   globalnie unikalne.
2. **Wspólny rdzeń kodu.** Dziś wszystko jest zaszyte pod Rybnik: stacja GIOŚ, id
   miasta w Tauronie, adresy scraperów, klub żużlowy. Przy drugim mieście to się
   rozjedzie. Zanim powstanie kolejna apka, warto wydzielić konfigurację miasta do
   jednego pliku i wariantów `productFlavors` w Gradle. Osobne repo per miasto oznacza
   naprawianie tego samego błędu pięć razy.

---

## Ile to realnie trwa

| Etap | Czas |
|---|---|
| Wniosek o D-U-N-S | 5–15 dni roboczych |
| Rejestracja i opłata | 30 minut |
| Weryfikacja firmy przez Google | 2–7 dni |
| Pierwsza weryfikacja aplikacji | kilka dni do 2 tygodni |

Licząc od dziś, realnie **3–5 tygodni** do publikacji. Dlatego wniosek o D-U-N-S
złóż dziś, a resztę rób równolegle.

## Co można zrobić od razu, nie czekając

Kod jest gotowy do wydania. W międzyczasie da się:

- wygenerować klucz podpisujący i zabezpieczyć go (krok 2 w [`PUBLISHING.md`](PUBLISHING.md)),
- zbudować i przetestować AAB na prawdziwym telefonie,
- ustalić identyfikator pakietu, bo po publikacji jest nie do zmiany,
- dopiąć zaległości z roadmapy, skoro i tak czekamy.
