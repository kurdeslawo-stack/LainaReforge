# Item Economy Analyzer

`ItemEconomyAnalyzer` is a standalone maintenance tool for ETAP 3. It reads only catalog entries
with complete `wiki` and `name` mappings and never writes `items.yml` or plugin runtime files.

The analyzer:

- downloads page wikitext through the MediaWiki API in sequential batches of at most 50 pages;
- stores page text and revision metadata in a separate deterministic cache;
- keeps short statements from the Wiki as evidence and reports system conclusions separately;
- uses conservative supply tags and leaves unsupported facts as `UNKNOWN`;
- treats `proposal.recyclable` as a non-binding proposal and never assigns shard values;
- sends missing, unclear, contradictory, and mixed-impact acquisition data to manual review.

Run from the repository root:

```powershell
.\tools\analyze-item-economy.ps1
```

Useful modes:

```powershell
# Discard the ETAP 3 page cache and fetch current page revisions sequentially.
.\tools\analyze-item-economy.ps1 -Refresh

# Generate only from the committed ETAP 3 cache, without HTTP requests.
.\tools\analyze-item-economy.ps1 -Offline
```

The default request interval is 250 ms and can be changed with `-DelayMilliseconds`.
