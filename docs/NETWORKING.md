# Networking (Ktor)

The HTTP layer: one client, three read-only API classes. Read before adding an API,
authentication, or retry.

For the two remote sources specifically, see [SUPABASE.md](SUPABASE.md) and
[YAHOO.md](YAHOO.md); this page is about the Ktor setup and the conventions an API
class follows.

---

## 1. What exists today

**One `HttpClient`**, bound in `AppModule` (`di/Modules.kt`), with the engine coming
from the platform module (OkHttp on Android, Darwin on iOS). Two plugins:

```kotlin
install(ContentNegotiation) { json(json) }
install(HttpTimeout) {
    requestTimeoutMillis = 60_000
    connectTimeoutMillis = 20_000
    socketTimeoutMillis  = 60_000
}
```

That is deliberately small. There is **no `Auth`, no `Logging`, no
`HttpRequestRetry`, no `HttpResponseValidator`, no `defaultRequest`**, no
`Result<T>` envelope and no `BaseKtorApiClient` base class. Three GET-only clients
do not need that machinery, and building it speculatively would be a framework with
one user.

§4 covers what to do when that stops being true.

---

## 2. The rule that protects the anon key

**Never put per-source headers in `defaultRequest`.**

The `HttpClient` is a singleton shared by the Supabase clients *and* `YahooChartApi`.
A client-wide `apikey` header would ship the Supabase anon key to Yahoo on every
chart fetch.

Auth headers are set **per request**, inside the API class that owns them.

---

## 3. Two error conventions, and how to choose

The two families behave differently on purpose. A fourth API should pick
consciously rather than copy whichever it saw first.

### Throw on non-2xx — the Supabase clients

```kotlin
if (!response.status.isSuccess()) {
    throw SupabaseHttpException(status = response.status.value, message = "…")
}
```

Use this when **a failed response means the operation failed** and the caller must
know. The repository catches it, maps it to a `RefreshFailure`, and the UI shows a
badge. Nothing is silently degraded.

### Return null on non-2xx — `YahooChartApi`

Use this when **a non-2xx is a routing signal rather than an error**: here it drives
the `query1 → query2` fallback, and "no data for this symbol" is an ordinary answer,
not a fault. The caller keeps whatever is cached.

### The rule

> Throw when the caller must react. Return null when absence is a legitimate answer
> the caller already knows how to render.

And in either case, at the repository boundary: **catch `CancellationException`
first and rethrow it**, before any broader catch. See
[ARCHITECTURE.md §3](ARCHITECTURE.md).

---

## 4. Adding auth (when a login arrives)

The pieces, in the order they become necessary:

1. **Do not put the token in `defaultRequest`** either — same reason as §2, unless by
   then every request genuinely goes to one host.
2. Install the **`Auth` plugin with `bearer { }`**, giving it `loadTokens` and
   `refreshTokens`. The plugin serialises concurrent refreshes for you, which is the
   part that is easy to get wrong by hand.
3. Keep tokens out of Room. They are credentials, not cache — platform secure
   storage behind an `expect`/`actual`.
4. **Only then** consider an `ApiError` sealed hierarchy. It earns its place when
   the UI has to distinguish 401 from 403 from a network failure; before that,
   `RefreshFailure`'s three cases carry the same information more cheaply.
5. `HttpRequestRetry` with exponential backoff — but **not** for Yahoo, which is
   rate-limited by frequency and where retrying makes an active throttle worse
   ([YAHOO.md §3](YAHOO.md)).

Plugin installation order matters: `ContentNegotiation` before anything that reads a
body, `Auth` before `HttpRequestRetry` so a retried request carries the refreshed
token.

---

## 5. Writing an API class

- Constructor takes the shared `HttpClient`; configuration parameters get defaults
  from generated config (`baseUrl`, `anonKey`). Koin's `skipDefaultValues` means
  `@Single` then injects only the client — the secrets mechanism stays untouched.
- Annotate with `@Single`; the component scan picks it up.
- **DTOs are private to the file** and declare only the fields actually read. The
  shared `Json` has `ignoreUnknownKeys = true`, so upstream adding a field cannot
  break deserialization.
- `@SerialName` for every wire name that is not already camelCase.
- Every field except the key is nullable. Upstream publishes partial rows, and the
  client must never crash on one.

---

## 6. Testing

**Fake at the engine, not at the API.** The API classes are final, and MockK is
JVM-only so it cannot be used in `commonTest` anyway. A `MockEngine` under the real
API class is both the least invasive seam and a free check of the real DTO
deserialization and pagination:

```kotlin
val engine = MockEngine { respond(content = fixtureJson, status = HttpStatusCode.OK, …) }
SupabaseScannerApi(HttpClient(engine) { install(ContentNegotiation) { json(json) } }, …)
```

**Never write a test that reaches a real host.** Full detail in
[TESTING.md §3](TESTING.md).
