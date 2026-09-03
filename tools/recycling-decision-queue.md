# Recycling Decision Queue

`RecyclingDecisionQueueGenerator` is a standalone maintenance tool. It combines mapped item
catalog identities with ETAP 3 acquisition facts and adds every remaining catalog identity as an
individual conservative `UNMAPPED` entry for human review. It is not registered in the Paper
plugin and never changes `items.yml` or shard values.

Run from the repository root:

```powershell
.\tools\generate-recycling-decision-queue.ps1
```

Generated files:

- `generated/recycling-decision-queue.yml` — grouped MAPPED items plus one record per UNMAPPED
  `material+CMD`;
- `generated/recycling-decision-queue-report.txt` — counts and validation summary.

Every generated decision starts as:

```yaml
decision:
  status: PENDING
  recyclable: null
  shards: null
  reviewed_by: null
  reviewed_at: null
  note: ""
```

Valid human decisions are:

- `PENDING`: `recyclable` and `shards` must remain `null`;
- `APPROVED`: `recyclable: true` and a positive integer `shards` value;
- `REJECTED`: `recyclable: false` and `shards: 0`.

Priority only determines review order. It is not an economic decision and does not change the
ETAP 3 system proposal. Generation fails validation on invalid decision semantics, missing
identities, duplicate logical items, cross-item `material+CMD` conflicts, lost Wiki mappings, or
any catalog identity that is missing from the queue. An `UNMAPPED` entry has blank `wiki`, one
identity, LOW priority and UNKNOWN acquisition/proposal; it can still receive a normal human
APPROVED or REJECTED decision in the review panel.

Optional CLI paths are available when invoking the Java class directly:

```text
--catalog path
--analysis path
--manual-review path
--output path
--report path
```
