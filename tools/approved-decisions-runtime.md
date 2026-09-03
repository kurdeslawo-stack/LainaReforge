# Approved Decisions Runtime

ETAP 6 kompiluje recenzowane decyzje z panelu do minimalnego, bezpiecznego
rejestru używanego przez plugin. Kluczem runtime zawsze jest para
`material:CustomModelData`.

## Kompilacja

Umieść eksport panelu jako `recycling-decisions.yml`, a następnie uruchom z katalogu
repozytorium:

```powershell
.\tools\compile-recycling-runtime.ps1
```

Skrypt czyta również `generated/recycling-decision-queue.yml` i zapisuje:

- `src/main/resources/recycling-runtime.yml` — resource dystrybuowany z pluginem;
- `generated/approved-decisions-runtime-report.txt` — podsumowanie kompilacji.

Można wskazać inne ścieżki parametrami `-Decisions`, `-Queue`, `-Output` i `-Report`.
Kompilator zapisuje wynik dopiero po pełnej walidacji. Jakikolwiek nieznany item,
niepoprawna decyzja, konflikt identity lub uszkodzony format kończy operację błędem
bez częściowego nadpisania runtime.

## Semantyka bezpieczeństwa

- `APPROVED` wymaga `recyclable: true` i dodatniej liczby shards.
- `REJECTED` wymaga `recyclable: false` i `shards: 0`.
- brak wpisu, brak poprawnej identity lub błędny config zawsze blokuje recycling.
- reload aktywuje immutable snapshot atomowo; przy błędzie zachowuje last-known-good.

Plik `recycling-decisions.yml` w repozytorium jest celowo pustym szablonem. Nie
zawiera wymyślonych decyzji ekonomicznych. Do produkcyjnego runtime należy użyć
rzeczywistego eksportu zatwierdzonego przez człowieka.
