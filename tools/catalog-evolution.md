# Catalog Evolution

Narzędzie aktualizuje katalog po zmianie `items.zip` bez automatycznych decyzji ekonomicznych.
Porównuje `material+CMD` oraz fingerprint definicji źródłowej z trwałym snapshotem.

Najpierw wykonaj dry run:

```powershell
.\tools\update-item-catalog.ps1 -ItemsZip .\items.zip -DryRun
```

Dry run nie zapisuje żadnych plików. Pokazuje liczbę `UNCHANGED`, `NEW`, `CHANGED`
i `REMOVED`; błędny ZIP lub konflikt kończy się kodem błędu.

Po sprawdzeniu raportu wykonaj aktualizację:

```powershell
.\tools\update-item-catalog.ps1 -ItemsZip .\items.zip
```

Zapis katalogu, snapshotu, aktywnej kolejki, panelu i raportów jest wykonywany jako jedna
transakcja plikowa. `recycling-decisions.yml` oraz produkcyjny `recycling-runtime.yml` nie są
modyfikowane.

- `UNCHANGED` zachowuje mapowanie, shards, logical ID i istniejącą decyzję.
- `NEW` otrzymuje `PENDING`, `UNMAPPED` i nigdy nie jest automatycznie zatwierdzany.
- `CHANGED` trafia pod nowy logical ID `changed::material:CMD`, otrzymuje `PENDING` i nie może
  odziedziczyć starego runtime.
- `REMOVED` znika z aktywnej kolejki, ale pozostaje w raporcie ewolucji.

Panel udostępnia kolejkę **Nowe i zmienione**, filtry `NEW`/`CHANGED` oraz porównanie
`BEFORE`/`AFTER`. Jeśli zmiana unieważnia lokalną decyzję, panel zapisuje systemowy event
`CATALOG_CHANGED` bez reviewera. Historia review pozostaje w osobnym localStorage.
