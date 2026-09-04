# Recycling Review Panel

Panel jest lokalnym, samodzielnym narzędziem do ręcznego przeglądu pełnej kolejki katalogu.
Nie jest częścią runtime pluginu i nie zapisuje do `items.yml`.

Panel obejmuje cały katalog: wpisy `MAPPED` zachowują dane Wiki i ekonomii, natomiast każdy
`UNMAPPED` reprezentuje dokładnie jedną identity `material+CMD`. Filtr **Mapping** pozwala
ograniczyć widok do jednej z tych grup. Dla `UNMAPPED` panel pokazuje badge **BRAK WIKI** i dane
techniczne bez tworzenia pustego linku Wiki.

Interfejs ma układ stanowiska recenzenta: na górze znajduje się postęp i szybkie kolejki,
w centrum kompletne dossier bieżącego itemu, a po prawej przyklejony panel decyzji. Na jednym
ekranie dostępne są identity, stan katalogu i mapowania, źródło zdobycia, evidence, propozycja
systemu, podobne itemy, statystyki wycen, ryzyko, bieżąca decyzja oraz trzy ostatnie wpisy
historii itemu. Układ przechodzi w jedną kolumnę na węższych ekranach.

## Generowanie

Z katalogu głównego repozytorium uruchom:

```powershell
.\tools\generate-recycling-review-panel.ps1
```

Generator czyta `generated/recycling-decision-queue.yml` i tworzy:

- `generated/recycling-review-panel/index.html`;
- `generated/recycling-review-panel-report.txt`.

HTML jest self-contained. Otwórz `index.html` bezpośrednio w przeglądarce; panel nie
wymaga serwera HTTP i nie pobiera danych kolejki z internetu.

Oficjalne logo Laina.pl znajduje się w `tools/assets/laina-logo.png`. Generator osadza
oryginalny PNG w HTML jako lokalny `data:` URI, dlatego branding działa także po skopiowaniu
samego `index.html` i nie wymaga internetu ani dodatkowego assetu obok panelu.

## Praca z decyzjami

Decyzje są przechowywane w `localStorage` bieżącej przeglądarki pod stabilnym
kluczem `laina-reforge.recycling-decisions.v1`. Nazwa reviewera jest ustawiana raz
na sesję przeglądarki. Sam plik kolejki pozostaje tylko do odczytu.

Zakładka **Historia** przechowuje append-only audit log pod osobnym kluczem
`laina-reforge.recycling-decisions.v1.history`. Rejestruje utworzenie, edycję i import
decyzji wraz ze stanem poprzednim i nowym. Nawigacja, wyszukiwanie oraz eksporty nie
tworzą wpisów. **EXPORT HISTORY** zapisuje osobny `recycling-decision-history.yml`,
który nie jest wejściem kompilatora runtime.

Regularnie używaj **EXPORT DECISIONS**, aby pobrać `recycling-decisions.yml`.
Eksport zawiera wyłącznie podjęte decyzje `APPROVED` i `REJECTED`; pominięte oraz
nieprzejrzane wpisy nie są eksportowane. Przed pobraniem panel pokazuje podsumowanie
i ostrzega, że brakujące decyzje pozostaną `NOT_CONFIGURED`. **BACKUP DECISIONS**
zapisuje ten sam walidowany format bez zmiany lokalnych danych.

Szybkie kolejki pozwalają przejść do pozycji `PENDING`, `HIGH`, `MAPPED`, `UNMAPPED`,
`APPROVED` lub `REJECTED`. Skróty klawiaturowe poza polami formularza:

- `A` — przenosi fokus do wyboru shards (nie zatwierdza automatycznie);
- `R` — odrzuca bieżący item;
- `S` — pomija bieżący item;
- `←` / `→` — poprzedni / następny item.

Wartość custom shards musi być liczbą całkowitą od 1 do 256. Zmiana istniejącej
decyzji jest jawna i zapisuje nowy czas `reviewed_at`.

Wybór wartości shards jest etapem analizy. Dopiero osobny przycisk **ZAPISZ APPROVED** tworzy
decyzję, dzięki czemu propozycje ekonomiczne i skrót `A` nie mogą zatwierdzić itemu samodzielnie.
Aktualny procent przeglądu oraz liczniki `MAPPED`/`UNMAPPED` odświeżają się natychmiast.

**IMPORT DECISIONS** przyjmuje plik wygenerowany przez panel. Import jest
odrzucany w całości, jeśli zawiera nieznany item, brakujące lub nadmiarowe pola,
duplikaty albo niepoprawną semantykę decyzji. Panel prosi o potwierdzenie, kiedy
import nadpisze istniejące lokalne decyzje.

**RESET LOCAL DECISIONS** pokazuje liczbę usuwanych decyzji i, jeśli są zapisane,
wymaga dwóch potwierdzeń. Nie usuwa historii. Osobny **RESET HISTORY** wymaga dwóch
potwierdzeń i nie zmienia aktualnego snapshotu decyzji. Przed resetem warto wykonać backup.

## Economy Review Assistant

Sekcja **Podobne itemy** dobiera maksymalnie osiem pozycji według jawnego,
deterministycznego podobieństwa typu technicznego, grupy `model_path`, tagów zdobycia,
statusu mappingu i system proposal. Statystyki porównawcze są pokazywane dopiero dla
co najmniej trzech zatwierdzonych podobnych itemów.

Kliknięcie wartości 1–5 albo wpisanie custom shards tylko przygotowuje analizę. Decyzja
`APPROVED` powstaje dopiero po osobnym kliknięciu **ZAPISZ APPROVED**. Ostrzeżenia
`OUTLIER HIGH`, `OUTLIER LOW` oraz `HIGH ECONOMY RISK` są informacyjne i nigdy nie
zmieniają decyzji ani wyceny.

Kolejka **Podejrzane wyceny** zbiera zapisane decyzje odstające od podobnych pozycji.
Zakładka **Ekonomia** pokazuje rozkład zatwierdzonych wycen, medianę, średnią, skrajne
wartości, outliery i liczbę pozycji wysokiego ryzyka. Kalkulator sprawdza symulowaną
sumę względem wspólnych limitów runtime: 256 shards na item oraz 4096 na transakcję.
Oglądanie analiz, zmiana filtrów i używanie kalkulatora nie tworzą historii decyzji.

## Ograniczenia

- brak backendu, REST API i bazy danych;
- brak automatycznego zapisu do kolejki lub `items.yml`;
- link do Wiki jest jedynym odnośnikiem sieciowym;
- eksportowane decyzje wymagają osobnego, przyszłego etapu walidacji i zastosowania.
