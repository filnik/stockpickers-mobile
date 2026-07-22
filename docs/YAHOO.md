# Yahoo Finance chart data

The price chart on the detail screen. Fetched **directly from the device**, cached
in Room per `(ticker, range)`.

Read this before changing the fetch, the ranges, or the caching — several of the
constraints here are not obvious and one of them is a licensing question.

---

## 1. The endpoint

```
https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=&interval=
```

Unofficial, undocumented, and **not** the `yfinance` Python library the upstream
pipeline uses. Nothing upstream constrains this design — but nothing validates it
either. It is this client's own.

Our tickers **are** Yahoo symbols (`AAPL`, `2330.TW`, `BPE.MI`, `8411.T`), so there
is no mapping table to maintain.

### A browser User-Agent is required

A default library agent gets `429`. This is not optional and not a workaround to
tidy away.

### query1 → query2 fallback

The two hosts throttle independently, so a non-2xx on the first is retried once on
the second. **`YahooChartApi` never loops beyond that** — see §3.

---

## 2. Ranges and intervals

`ChartRange` carries Yahoo's own tokens:

| Range | `range` | `interval` | Intraday |
|---|---|---|---|
| 1D | `1d` | `5m` | yes |
| 1W | `5d` | `30m` | yes |
| 1M / 3M / 6M / 1Y | `1y` | `1d` | no |

**`isIntraday` is a string comparison against `"1d"`.** Adding an interval like
`1wk` would silently make it "intraday" and give it the 5-minute TTL instead of six
hours. `ChartRangeTest` pins this.

**`rangeKey` is part of the Room composite primary key.** Renaming an enum constant
silently invalidates every cached series — also pinned by a test.

### One fetch warms four ranges

The four daily ranges all use `interval=1d`, so a single `range=1y` fetch contains
them all. `refreshDailyGroup` slices it into four Room rows. Switching among daily
chips is then served from Room with **zero** extra network calls — and one request
instead of four is materially kinder to a per-IP limit.

Freshness is gated on the 1Y row, since all four are written together.

---

## 3. Throttling, and what we deliberately do not do

Yahoo throttles by **per-IP frequency**. A client calling once per ticker from its
own device IP, backed by a Room cache, is low-frequency and fine.

### Why there is no proxy Worker

A shared server-side proxy would **concentrate every user onto a few egress IPs** —
strictly worse than the current design, not better. The mitigation for a per-IP
limit is caching, not centralising.

### Failures are graceful, never retried in a loop

A 429, an offline device or a parse error leaves whatever is cached on screen. The
catch in `refreshPriceSeries` swallows deliberately (`catch (_: Throwable)`), and
`CancellationException` is rethrown first.

### What upstream has that we do not

Worth knowing, because it bounds how hard this client may ever push:

- a **persistent cross-process 429 cooldown** (30 minutes, file-locked),
- a **circuit breaker** after consecutive failures,
- a **500 ms global inter-request throttle**.

Upstream measured 44–93% failure rates without them, and documents ban durations
from under an hour to over a day. Our per-request behaviour is weaker. **If a
screen ever fans out N concurrent chart requests on a cache miss, that is the
pattern that produced those numbers** — add a throttle before adding parallelism.

---

## 4. Adjusted prices

Read `indicators.adjclose[0].adjclose` when present, falling back to
`indicators.quote[0].close`.

**This matters.** The raw `quote.close` series is not adjusted for splits or
dividends, so a 10:1 split draws a cliff that never happened — and, worse, the
chart then disagrees with the momentum and clenow figures rendered on the same
screen, which upstream computes on adjusted prices.

`adjclose` is **daily-only**; intraday responses have no such block, which is why
the fallback exists rather than being an error. Over hours, no corporate action can
have intervened anyway.

---

## 5. Other quirks worth knowing

- **`chartPreviousClose`, not `previousClose`.** The period change is computed
  against the close *before the requested window*, so it is the selected range's
  move rather than the day's. For a sliced daily range, the baseline is the close
  just before that slice.
- **Null closes.** Yahoo emits `null` for missing sessions; points are paired with
  their timestamp and the nulls dropped.
- **Key the series by the REQUESTED ticker**, not `meta.symbol` — the echo can
  differ in case or suffix, and the cache is keyed on what we asked for.
- **Both charts auto-scale Y to the data**, not from zero: an intraday range looks
  flat otherwise.

---

## 6. TTLs

| Kind | TTL |
|---|---|
| Intraday (1D, 1W) | ~5 minutes |
| Daily (1M…1Y) | ~6 hours |

The six-hour figure coincides with upstream's own hard rule for its scanner cache,
which is a reassuring accident rather than a shared constant.

---

## 7. Licensing

The v8 endpoint is **unofficial and personal-use**. For a store or commercial
release, front it with a swappable server-side source; for this portfolio app,
direct is a considered choice, not an oversight.

---

## 8. Testing

**Never write a test that reaches real Yahoo.** Use a fixture over Ktor's
`MockEngine` — see [TESTING.md §3](TESTING.md). `TickerRepositoryImplTest` has
fixtures for both the adjusted daily shape and the intraday shape without
`adjclose`.
