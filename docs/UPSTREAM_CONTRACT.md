# The upstream contract

**Read this before touching the leaders query, any threshold, or any unit.**

The scanner's rules belong to the upstream Python pipeline. This client *reads*
them; it does not invent, improve or re-derive them. This page records what the
contract actually is, where the authoritative source for each clause lives, and —
importantly — the places where upstream's own documentation is wrong.

---

## 1. Units — the most dangerous table in the project

Getting one of these wrong ships a number that is 100× off, with no crash and no
visual tell. `ui/Formatting.kt` encodes them and `FormattingTest` pins them.

| Field | Unit | Rendering | Authority |
|---|---|---|---|
| `mom_1m`, `mom_2m`, `mom_3m`, `mom_12m` | decimal fraction (`0.08` = +8%) | ×100, signed | `core/indicators.py::calc_momentum` |
| `ann_mom` | **decimal fraction** — same as the others | ×100 | `services/supabase_writer.py` |
| `clenow` | **already ×100**, floored at 0 | pass through | `core/indicators.py` (`score = ann * r2 * 100`) |
| `r2` | ratio, 0..1 | 2 decimals | — |
| `peg`, `forward_pe` | plain ratio | 2 decimals, no `%` | — |
| `roic` | decimal fraction | ×100, unsigned (a level, not a delta) | mirrors the web's `RichPickCard` |
| `vol` | decimal fraction | ×100 | — |
| `debt_equity` | **percent** (`300.0` = 300%) | — | — |

### ⚠️ Upstream's own doc is wrong about `ann_mom`

`tech-docs/reference/scanner_cache.md` states that `ann_mom` is in percent units.
**It is not.** The correct contract is in `services/supabase_writer.py`, whose
comment records the incident that settled it (2026-06-10): a `/100` fallback made
a +45% trend render as "+0,4%". The web client agrees with the writer, not the doc.

A port that consulted the doc would divide every `mom_12m` fallback by 100. This
client uses `mom_12m ?: annMom` with no rescaling (`TickerDto.annMom`), which is
what upstream's writer and web client both do.

### `clenow` is already scaled

Typical values run from ~45 (BPER) to ~4400 (a US NAND name). Multiplying again
would give 440 000. `formatClenow` deliberately passes it through.

---

## 2. The leaders query

**It is not SQL upstream.** The web client fetches the whole table and applies the
predicate in memory; there is a Python twin in `strategies/pe_switch.py`. This
client ports the predicate into `ScannerDao.observeMomentumLeaders`'s SQL so that
filtering and ordering happen in SQLite over the whole cached universe.

Same semantics, better mechanics — but it means **the SQL here is a port, and it
must be kept faithful**. The executable spec upstream is `web/lib/queries.ts`
(plus its `queries-leaders.test.ts`).

### The qualifying predicate, and its three asymmetric fail-modes

This is the part that is easy to get subtly wrong, because the three exclusions do
**not** treat missing data the same way:

| Field | Rejects when | Missing / null → |
|---|---|---|
| `quality_gate.passes_filters` | not explicitly `true` | **REJECTED** — fail-safe |
| `wyckoff_markdown` | `=== true` | KEPT — fail-open |
| `duplicate_of` | non-empty string | KEPT — fail-open |

Plus: the country must be in a bucket, the ranking window must be present, and
`clenow` must be `> 0`.

**Why the quality gate fails safe:** a row missing the Python verdict has not been
evaluated, and an un-evaluated row must never reach the board. Upstream's comment
names the incident — using `=== false` once let un-evaluated rows through
("VSXY-class leak"). In this client's SQL, `qualityPasses = 1` gives the fail-safe
behaviour for free, because `NULL = 1` is `NULL`. **Writing `!= 0` would fail
open** and leak exactly those rows.

### Read `passes_filters` as an opaque boolean

Do not recompute the quality thresholds locally, and do not "improve" one. The
gate is upstream's, it changes there, and a local copy silently diverges. Upstream
forbids this explicitly, for the same incident.

### Known divergences — deliberate, do not "fix"

1. **We page past the 1000-row cap; the website does not.** PostgREST caps a single
   response at 1000 rows whatever `limit` says, and the table holds ~1800. The web
   client issues a bare `.select()` and is silently truncated. `SupabaseScannerApi`
   pages with the `Range` header, so **our leaders can legitimately differ from the
   published site's**. We are the correct one. Do not align downward.
2. **No merge pass.** Upstream stitches three separate leader queries together only
   because a server-side top-N would be dominated by US/Taiwan and leave the ITA and
   ASIA chips empty. Room holds the whole universe, so the chip is a plain `WHERE`
   applied *before* the `LIMIT` — one query, same result. Do not port the merge.
3. **The Python twin has an extra filter the web lacks** — `should_include_in_pe_switch`
   drops non-Trade-Republic-tradable and FX-distorted suffixes. The web (and this
   client) keep them. Upstream's claim that the two produce the same tickers is not
   strictly true. If parity with the *Python* leaders is ever wanted, that is the
   missing piece.

---

## 3. Geo buckets

`USA` / `ITA` / `ASIA`, where ASIA is **Japan + South Korea + Taiwan**. Countries
outside the three buckets are excluded entirely — including from the "Tutti" count,
which is why the chip counts sum to the total.

The country list in `ScannerDao`'s SQL is the **fourth copy** of this mapping
(upstream Python, the web's `BUCKET_COUNTRIES`, the Python mirror, and here).
Accepted debt: an offline-first client cannot call a source of truth it may not be
able to reach. **Add a country upstream → add it here too.** A fifth copy is the
signal to publish the buckets as data instead.

---

## 4. Row lifecycle

- **Every field except `ticker` is nullable.** The pipeline publishes rows in
  varying completeness; the client must never crash on a partial row. `price_eur`
  is currently null for *every* row, so the "—" is correct, not a bug.
- **Rows are HARD-DELETED upstream**, in batches, on every run — delisted names and
  symbols that left the universe. A sync that only upserts would keep them forever
  and they would go on qualifying. `refresh()` therefore prunes to the published
  set (`ScannerDao.replaceAll`), but **only after a complete, successful fetch**:
  a truncated or empty response must never prune, or the offline-first promise is
  broken. `SupabaseScannerApi` throws rather than return a partial list, and an
  empty publish is treated as a server fault.

---

## 5. Written profiles (`descriptions_cache`)

- **Most tickers have no row at all.** Absence is the ordinary case, so the profile
  card is simply omitted — never an error state.
- `next_earnings` is **polymorphic upstream**: an object on some rows, a bare string
  on others. Upstream's own TypeScript types do not model this and are wrong. Our
  workaround (parse the object, fall back to putting the string in `consensus`, drop
  the block on any other shape) is correct and should stay.
- **`days_away` is recomputed from the date, never replayed.** It is a snapshot that
  drifts by a day per day.
- The text is a **mixed EN/IT** corpus with no freshness gate on read upstream.
  Italian rows are not a bug, and adding a freshness filter would blank most cards.
