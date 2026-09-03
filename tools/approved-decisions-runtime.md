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

## Bezpieczny deployment

Pełny workflow produkcyjny:

1. wyeksportuj `recycling-decisions.yml` z Recycling Review Panel;
2. skompiluj i zweryfikuj runtime powyższym poleceniem;
3. opcjonalnie skopiuj poprawny wynik do katalogu danych serwera przez parametr
   `-DeployPath`;
4. na serwerze wykonaj `/reforge reload --check`;
5. dopiero po poprawnym wyniku wykonaj `/reforge reload`.

Przykład deploymentu (ścieżkę dobiera operator):

```powershell
.\tools\compile-recycling-runtime.ps1 `
  -DeployPath "D:\ścieżka\serwera\plugins\LainaReforge\recycling-runtime.yml"
```

Bez `-DeployPath` skrypt nie dotyka katalogu serwera. Kopia deploymentowa powstaje
dopiero po pomyślnej kompilacji i walidacji. Aktualizacja samego JAR-a celowo nie
nadpisuje istniejącego pliku w `plugins/LainaReforge`, aby nie skasować świadomie
wdrożonej konfiguracji.

## Semantyka bezpieczeństwa

- `APPROVED` wymaga `recyclable: true` i dodatniej liczby shards.
- `REJECTED` wymaga `recyclable: false` i `shards: 0`.
- brak wpisu, brak poprawnej identity lub błędny config zawsze blokuje recycling.
- reload aktywuje immutable snapshot atomowo; przy błędzie zachowuje last-known-good.
- pojedynczy item może wypłacić najwyżej 256 shards, a cała transakcja najwyżej 4096.

Plik `recycling-decisions.yml` w repozytorium jest celowo pustym szablonem. Nie
zawiera wymyślonych decyzji ekonomicznych. Do produkcyjnego runtime należy użyć
rzeczywistego eksportu zatwierdzonego przez człowieka.
