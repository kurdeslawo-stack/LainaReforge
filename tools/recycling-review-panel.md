# Recycling Review Panel

Panel jest lokalnym, samodzielnym narzędziem do ręcznego przeglądu pełnej kolejki katalogu.
Nie jest częścią runtime pluginu i nie zapisuje do `items.yml`.

Panel obejmuje cały katalog: wpisy `MAPPED` zachowują dane Wiki i ekonomii, natomiast każdy
`UNMAPPED` reprezentuje dokładnie jedną identity `material+CMD`. Filtr **Mapping** pozwala
ograniczyć widok do jednej z tych grup. Dla `UNMAPPED` panel pokazuje badge **BRAK WIKI** i dane
techniczne bez tworzenia pustego linku Wiki.

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

## Praca z decyzjami

Decyzje są przechowywane w `localStorage` bieżącej przeglądarki pod stabilnym
kluczem `laina-reforge.recycling-decisions.v1`. Nazwa reviewera jest ustawiana raz
na sesję przeglądarki. Sam plik kolejki pozostaje tylko do odczytu.

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

**IMPORT DECISIONS** przyjmuje plik wygenerowany przez panel. Import jest
odrzucany w całości, jeśli zawiera nieznany item, brakujące lub nadmiarowe pola,
duplikaty albo niepoprawną semantykę decyzji. Panel prosi o potwierdzenie, kiedy
import nadpisze istniejące lokalne decyzje.

**RESET LOCAL DECISIONS** pokazuje liczbę usuwanych decyzji i, jeśli są zapisane,
wymaga dwóch potwierdzeń. Przed resetem warto wykonać backup.

## Ograniczenia

- brak backendu, REST API i bazy danych;
- brak automatycznego zapisu do kolejki lub `items.yml`;
- link do Wiki jest jedynym odnośnikiem sieciowym;
- eksportowane decyzje wymagają osobnego, przyszłego etapu walidacji i zastosowania.
