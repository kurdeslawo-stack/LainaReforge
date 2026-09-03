# LainaReforge — instrukcja od zera

LainaReforge składa się z **dwóch osobnych części**:

1. **lokalnego panelu w przeglądarce** — służy do przeglądania itemów i ustawiania, które można recyklingować oraz ile shardów mają dawać,
2. **pluginu Paper** — działa na serwerze Minecraft, rozpoznaje item i wykonuje decyzję przygotowaną wcześniej w panelu.

Najprostszy schemat działania:

```text
panel HTML
   ↓
recycling-decisions.yml
   ↓
compiler / walidacja
   ↓
recycling-runtime.yml
   ↓
plugin Paper
```

Panel **nie wymaga uruchomionego serwera Minecraft**. Jest gotowym plikiem HTML działającym lokalnie w przeglądarce.

---

## 1. Wymagania

Do samego panelu wystarczy zwykła przeglądarka.

Do budowania pluginu i kompilowania runtime potrzebne są:

- Git,
- Java 25,
- Maven,
- PowerShell na Windowsie.

Maven powinien być dostępny jako komenda `mvn` w terminalu.

Sprawdzenie:

```powershell
java -version
mvn -version
git --version
```

---

## 2. Pobranie projektu

```powershell
git clone https://github.com/kurdeslawo-stack/LainaReforge.git
cd LainaReforge
git switch feature/review-workflow-ux
```

Aktualna wersja panelu i workflow znajduje się na branchu:

```text
feature/review-workflow-ux
```

---

## 3. Uruchomienie panelu

Panel znajduje się tutaj:

```text
generated/recycling-review-panel/index.html
```

Można po prostu otworzyć `index.html` dwuklikiem.

Na Windowsie można też użyć PowerShella:

```powershell
Start-Process ".\generated\recycling-review-panel\index.html"
```

Nie trzeba:

- uruchamiać Paper,
- uruchamiać pluginu,
- uruchamiać lokalnego serwera WWW,
- kompilować panelu.

Panel jest już wygenerowany jako samodzielny HTML.

---

## 4. Jak działa panel

Panel pokazuje itemy z katalogu Lainy i pozwala wykonać ręczny review ekonomii.

Dla itemu można ustawić:

### APPROVED

Item może być recyklingowany.

Przy zatwierdzeniu podaje się liczbę shardów, np.:

```text
APPROVED
3 shardy
```

### REJECTED

Item nie może być recyklingowany:

```text
REJECTED
```

### PENDING

Item nie ma jeszcze podjętej decyzji i nie powinien działać w recyclerze.

Panel posiada między innymi:

- wyszukiwarkę,
- filtrowanie,
- wyszukiwanie po `material:CMD`,
- kolejki review,
- historię zmian,
- notatki,
- eksport decyzji,
- backup danych.

---

## 5. Ważne: gdzie panel zapisuje dane

Podczas pracy decyzje są zapisywane **lokalnie w przeglądarce**.

Oznacza to, że otwarcie panelu na innym komputerze lub w innej przeglądarce nie przeniesie automatycznie dotychczasowych decyzji.

Dlatego podczas większego review warto regularnie używać:

- backupu decyzji,
- eksportu YAML,
- eksportu historii.

Nie należy traktować danych zapisanych wyłącznie w przeglądarce jako jedynej kopii.

---

## 6. Eksport decyzji z panelu

Po wykonaniu review eksportujemy z panelu plik:

```text
recycling-decisions.yml
```

Plik należy umieścić w głównym katalogu repozytorium:

```text
LainaReforge/
├── pom.xml
├── recycling-decisions.yml
├── items.zip
├── generated/
├── src/
└── tools/
```

Eksport zastępuje pusty lub wcześniejszy plik `recycling-decisions.yml`.

Ten plik jest **źródłem ludzkich decyzji**, ale plugin na serwerze nie korzysta bezpośrednio z całego panelu.

---

## 7. Kompilowanie decyzji do runtime

Decyzje z panelu są walidowane i zamieniane na runtime używany przez plugin.

Uruchom:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\compile-recycling-runtime.ps1"
```

Workflow:

```text
recycling-decisions.yml
        ↓
walidacja
        ↓
recycling-runtime.yml
```

Gotowy runtime powstaje tutaj:

```text
src/main/resources/recycling-runtime.yml
```

Raport kompilacji:

```text
generated/approved-decisions-runtime-report.txt
```

Jeżeli decyzje są błędne, compiler powinien zakończyć pracę błędem zamiast generować wadliwy runtime.

---

## 8. Budowanie pluginu

W głównym katalogu projektu:

```powershell
mvn clean package
```

Gotowy JAR pojawi się tutaj:

```text
target/LainaReforge-0.1.0-SNAPSHOT.jar
```

Plugin jest budowany pod Java 25 i Paper API 26.2.

---

## 9. Instalacja na serwerze

Gotowy JAR wrzucamy do:

```text
plugins/
```

Po uruchomieniu serwera plugin posiada własny folder danych:

```text
plugins/LainaReforge/
```

Runtime używany przez działający plugin znajduje się tutaj:

```text
plugins/LainaReforge/recycling-runtime.yml
```

Jeżeli pliku jeszcze nie ma, plugin przy starcie zapisze domyślny `recycling-runtime.yml` znajdujący się wewnątrz JAR-a.

---

## 10. Aktualizacja samych wartości bez przebudowy pluginu

Zmiana ekonomii nie wymaga za każdym razem przebudowy całego JAR-a.

Przykład:

```text
item dawał 3 shardy
↓
ma dawać 5 shardów
```

Workflow:

```text
panel
↓
zmiana wartości
↓
eksport recycling-decisions.yml
↓
compile-recycling-runtime.ps1
↓
nowy recycling-runtime.yml
↓
podmiana pliku na serwerze
↓
/reforge reload --check
↓
/reforge reload
```

Najpierw warto zawsze uruchomić:

```text
/reforge reload --check
```

Ta komenda sprawdza konfigurację i runtime, ale **nie aktywuje zmian**.

Jeżeli wszystko jest poprawne:

```text
/reforge reload
```

Plugin aktywuje nowy snapshot bez restartowania całego serwera.

---

## 11. Automatyczne wdrożenie runtime do folderu serwera

Jeżeli repo i serwer znajdują się na tej samej maszynie, compiler może od razu skopiować poprawny runtime do folderu pluginu.

Przykład:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\tools\compile-recycling-runtime.ps1" `
  -DeployPath "C:\SCIEZKA_DO_SERWERA\plugins\LainaReforge\recycling-runtime.yml"
```

Skrypt najpierw generuje i waliduje runtime, a dopiero potem wykonuje podmianę pliku docelowego.

Po deployu:

```text
/reforge reload --check
/reforge reload
```

---

## 12. Najważniejsze komendy Minecraft

### Otworzenie recyclera

```text
/reforge
```

Aliasami są również:

```text
/recykling
/recycler
```

### Sprawdzenie itemu trzymanego w ręce

```text
/reforge why
```

Pokazuje między innymi:

- czy item został rozpoznany,
- czy recycling jest dozwolony,
- liczbę shardów,
- reason code,
- źródło reguły.

### Diagnostyka itemu

```text
/reforge inspect
```

Pokazuje dane techniczne itemu, w tym material i CustomModelData, jeżeli są dostępne.

### Sprawdzenie wartości konkretnego `material:CMD`

```text
/reforge value <material:CMD>
```

Przykład:

```text
/reforge value golden_carrot:2350222
```

### Sprawdzenie nowych / nierozpoznanych customów

```text
/reforge pending
```

Plugin posiada Discovery Queue dla customowych itemów, które zostały zauważone na serwerze, ale nie są jeszcze skonfigurowane.

### Audyt systemu

```text
/reforge audit
```

Pokazuje między innymi stan konfiguracji, liczbę skonfigurowanych itemów, pending oraz liczbę aktywnych wpisów runtime.

### Sprawdzenie konfiguracji bez aktywacji

```text
/reforge reload --check
```

### Aktywacja poprawnej konfiguracji

```text
/reforge reload
```

Komendy administracyjne wymagają permisji:

```text
lainareforge.admin
```

---

## 13. Jak plugin rozpoznaje item

Dla runtime recyclera podstawową tożsamością customowego itemu jest:

```text
material + CustomModelData
```

Przykład:

```text
apple:2350429
```

Sam CMD nie jest traktowany jako globalnie unikalny, ponieważ ta sama wartość CMD może występować na różnych materiałach.

Dlatego poprawna identity wygląda np. tak:

```text
apple:2350429
```

a nie tylko:

```text
2350429
```

---

## 14. Co dzieje się z nieznanym itemem

System działa **fail-closed**.

Jeżeli gracz wrzuci customowy item, którego `material:CMD` nie znajduje się w zatwierdzonym runtime, plugin nie zgaduje wartości i nie przyznaje shardów.

Taki item pozostaje zablokowany jako nieskonfigurowany.

W praktyce:

```text
nowy / niezatwierdzony item
↓
NOT_CONFIGURED
↓
recycling zablokowany
```

To zabezpiecza ekonomię przed przypadkowym dodaniem nowego customu z automatyczną wartością.

Nieznane customy mogą być widoczne przez:

```text
/reforge pending
```

---

## 15. Bezpieczeństwo reloadu

Plugin nie powinien aktywować uszkodzonego runtime.

Przy błędnej konfiguracji zachowuje ostatni poprawny snapshot zamiast podmieniać go wadliwymi danymi.

Zalecany workflow przy każdej zmianie produkcyjnej:

```text
1. backup decyzji
2. eksport z panelu
3. kompilacja runtime
4. sprawdzenie raportu
5. deploy runtime
6. /reforge reload --check
7. /reforge reload
8. /reforge audit
```

---

## 16. Szybki test po instalacji

Po wrzuceniu pluginu na testowy serwer:

```text
/reforge audit
```

Następnie sprawdzić wybrany item:

```text
/reforge inspect
/reforge why
```

Można również sprawdzić jego policy bezpośrednio:

```text
/reforge value material:CMD
```

Na koniec otworzyć GUI:

```text
/reforge
```

i sprawdzić poprawny oraz zablokowany item.

---

# TL;DR

Jeżeli interesuje Cię tylko normalna obsługa systemu:

```text
1. Otwórz generated/recycling-review-panel/index.html
2. Ustaw APPROVED / REJECTED i wartość shardów
3. Wyeksportuj recycling-decisions.yml
4. Uruchom tools/compile-recycling-runtime.ps1
5. Wdróż recycling-runtime.yml do plugins/LainaReforge/
6. W MC wykonaj /reforge reload --check
7. Jeśli jest OK, wykonaj /reforge reload
```

Panel odpowiada za **zarządzanie decyzjami**, a plugin Minecraft za **wykonywanie tych decyzji na serwerze**.
