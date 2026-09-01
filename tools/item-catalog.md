# Item Catalog Generator

Generator reads Minecraft item-model definitions from `items.zip` and writes:

- `src/main/resources/items.yml`
- `generated/item-catalog-report.txt`

It is a build-time maintenance tool and is not connected to the plugin lifecycle.

From the repository root, run:

```powershell
.\tools\generate-item-catalog.ps1
```

Custom paths can be supplied with `-SourceZip`, `-CatalogPath`, and `-ReportPath`.
After the Java sources have already been compiled, `-SkipCompile` avoids running Maven again.

The command exits with code `2` when one `material+CMD` key points to different model definitions.
Invalid CMD values and malformed input also prevent `items.yml` from being overwritten.
