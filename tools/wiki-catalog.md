# Wiki Catalog Mapper

`WikiCatalogMapper` is an offline-capable maintenance tool. It is not registered in the Paper
plugin lifecycle and does not affect recycler runtime behavior.

The mapper:

- treats the complete `model_path` as catalog identity while using its `model` basename to
  match a `.png` filename without regard to case, spaces, underscores, or URL encoding;
- sends every basename shared by multiple distinct `model_path` values to manual review as
  `BASENAME_COLLISION` instead of copying one Wiki mapping to all paths;
- reads image usage and page categories through the MediaWiki API;
- accepts exactly one page directly assigned to `Kategoria:Przedmioty`;
- leaves uncertain `wiki` and `name` fields empty;
- never changes `shards`;
- writes a deterministic API cache and manual-review reports under `generated/`.

Run from the repository root:

```powershell
.\tools\map-wiki-catalog.ps1
```

Useful modes:

```powershell
# Ignore the current cache and download a new snapshot sequentially.
.\tools\map-wiki-catalog.ps1 -Refresh

# Use only cached data and make no HTTP requests.
.\tools\map-wiki-catalog.ps1 -Offline
```

Only actual API requests are rate-limited; cache hits do not wait. The default interval is 250 ms
and can be changed with `-DelayMilliseconds`.
