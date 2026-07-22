# Supabase / PostgREST

How this client reads the two published tables. For what the *values mean* — units,
the qualifying predicate, the fail-safe gate — see
[UPSTREAM_CONTRACT.md](UPSTREAM_CONTRACT.md); this page is about transport.

Read-only, anon key, two tables, no writes ever.

---

## 1. The rule that bites first: the 1000-row cap

**PostgREST caps a single response at 1000 rows, whatever `limit` says.** The
`scanner_cache` table holds ~1800. A naive `GET` therefore returns a silently
truncated universe and corrupts the board — no error, no warning, just missing
leaders.

`SupabaseScannerApi` pages with the `Range` header until a short page comes back:

```
Range-Unit: items
Range: 0-999, then 1000-1999, …
```

Two properties this code must keep:

- **It throws rather than return a partial list.** There is a `MAX_ROWS` stop, and
  hitting it is an error, not a truncation — `refresh()` prunes the local cache
  against whatever comes back, so an incomplete answer would delete real rows.
- **An empty response is a fault, not an empty universe.** Treated as
  `RefreshFailure.SERVER`, leaving the cache intact.

> The published website does *not* page, so its leaders are truncated at 1000 rows
> while ours are not. Ours differ from the site, and ours are right. See
> [UPSTREAM_CONTRACT.md §2](UPSTREAM_CONTRACT.md).

---

## 2. Auth headers — never in `defaultRequest`

```kotlin
header("apikey", anonKey)
header("Authorization", "Bearer $anonKey")
```

Set **per request**, never on the client. The `HttpClient` is a singleton shared
with `YahooChartApi`; a client-wide header would ship the Supabase anon key to a
third party on every chart fetch.

The key itself comes from `local.properties` → a generated `SupabaseConfig`, which
is git-ignored. **Never commit it, never move it into a tracked file, never paste it
into a test fixture.**

---

## 3. The two tables

### `scanner_cache`

The board and the detail read-out. The `data` JSONB payload is flattened by the
`select` projection:

```
select=ticker,name,country,sector,price_eur,updated_at,
       clenow:data->clenow,mom_1m:data->mom_1m,…,ann_mom:data->ann_mom,
       quality_gate:data->quality_gate,wyckoff_markdown:data->wyckoff_markdown,
       duplicate_of:data->duplicate_of
```

**Keep this projection in sync with the web client.** A field that is not selected
arrives as null and is indistinguishable from a genuinely missing value.

Every column except `ticker` is nullable — see
[UPSTREAM_CONTRACT.md §4](UPSTREAM_CONTRACT.md).

### `descriptions_cache`

The written profile on the detail screen: description, pros/cons, next earnings.
Keyed by **uppercase** ticker, with the content inside two JSONB blocks (`timeless`
and `current`) that are routinely `{}`.

**Most tickers have no row.** That is the ordinary case, not an error.

#### Do NOT reproduce the web's `.maybeSingle()`

The web sends `Accept: application/vnd.pgrst.object+json`, which makes PostgREST
answer **406 on zero rows**. Here, zero rows is the majority outcome. Ask for an
array and take the first element.

---

## 4. Two clocks, kept apart

| Clock | Means | Drives |
|---|---|---|
| `updated_at` + `ttl_days` (upstream) | how old the TEXT is | a freshness badge |
| `fetchedAt` (local) | how old OUR COPY is | whether to refetch |

Fusing them would re-download on every open precisely for the stalest rows — the
ones whose text upstream has not refreshed in a while. They are unrelated
questions; keep them apart.

---

## 5. Tombstones

A ticker with **no** profile upstream still gets a row written locally, with all
content null and `fetchedAt` set. Without it the TTL gate has no `fetchedAt` to
check, so the majority case (no profile) would hit the network on every visit to
the screen.

This deliberately differs from `refreshPriceSeries`, where a miss writes nothing —
there, a miss is rare.

---

## 6. Permissions

The anon role has **`SELECT` only**, on the public tables. Any write attempt fails
at the privilege layer, which is the correct backstop for a read-only client.

Several upstream tables are `is_public = false`; the anon key gets **zero rows**
from them, not an error. If a future feature needs one, it needs an authenticated
session — that is a real feature, not a config tweak.

---

## 7. Schema changes

The local cache is disposable: the Room builder uses
`fallbackToDestructiveMigration(dropAllTables = true)`. A schema change here is a
**version bump with no Migration** — do not hand-write migrations for these tables.
The next launch re-syncs from upstream.
