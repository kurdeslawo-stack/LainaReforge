# Recycling Review Panel

Panel jest lokalnym, samodzielnym narzędziem do ręcznego przeglądu kolejki ETAPU 4.
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
nieprzejrzane wpisy nie są eksportowane.

**IMPORT DECISIONS** przyjmuje plik wygenerowany przez panel. Import jest
odrzucany w całości, jeśli zawiera nieznany item, brakujące lub nadmiarowe pola,
duplikaty albo niepoprawną semantykę decyzji. Panel prosi o potwierdzenie, kiedy
import nadpisze istniejące lokalne decyzje.

**RESET LOCAL DECISIONS** usuwa wszystkie decyzje zapisane pod kluczem panelu po
wyraźnym potwierdzeniu. Przed resetem warto wykonać eksport.

## Ograniczenia

- brak backendu, REST API i bazy danych;
- brak automatycznego zapisu do kolejki lub `items.yml`;
- link do Wiki jest jedynym odnośnikiem sieciowym;
- eksportowane decyzje wymagają osobnego, przyszłego etapu walidacji i zastosowania.
