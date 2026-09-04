# LainaReforge 0.1.0-rc1

Release Candidate zamraża zakres funkcjonalny i sprawdza cały przepływ danych bez
wprowadzania automatycznych decyzji ekonomicznych.

## Preflight

Uruchom z katalogu repozytorium:

```powershell
.\RELEASE-PREFLIGHT.cmd
```

Narzędzie sprawdza Java 25, kompletność zasobów, 1757 identities katalogu,
pełne pokrycie kolejki, zgodność fingerprintu panelu, snapshot, składnię
`recycling-decisions.yml`, kompilację decyzji w pamięci, fail-closed runtime oraz
spójność wersji `pom.xml` i `plugin.yml`. Nie wdraża ani nie zmienia runtime.

## Build RC

```powershell
& "C:\CodexProjects\apache-maven-3.9.16\bin\mvn.cmd" `
  "-Dmaven.repo.local=C:\CodexProjects\.m2\repository" -B clean package
```

Artefakt: `target/LainaReforge-0.1.0-rc1.jar`.

Fixture smoke-testów powstają wyłącznie pod `target/`; nie wolno kopiować ich do
`src/main/resources`. Produkcyjny `recycling-decisions.yml` i
`recycling-runtime.yml` pozostają puste, dopóki właściciel serwera nie wyeksportuje
i nie zatwierdzi rzeczywistych decyzji.
