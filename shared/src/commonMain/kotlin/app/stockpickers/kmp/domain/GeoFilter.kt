package app.stockpickers.kmp.domain

/**
 * The country chips on the leaders board — mirror of the upstream web client's
 * `GeoBucket`, plus an [ALL] entry for the unfiltered board ("Tutti").
 *
 * Only the bucket KEYS and labels live here. The country strings each bucket
 * expands to are in the DAO's SQL, because filtering runs in SQLite — see the
 * mirroring note on `ScannerDao.observeMomentumLeaders`.
 *
 * ASIA is Japan + South Korea + Taiwan (native KRX / TSE-JP / TSE-TW; NO Hong
 * Kong / China). Every other country is already excluded upstream of the chips
 * by the quality gate's BUCKET_COUNTRIES, so — unlike the web's dip board, which
 * needs an "Altri" bucket to stay a partition — us + it + asia == all here by
 * construction. That is why there is no "other" chip.
 *
 * Labels are the web's verbatim (Italian), matching `GEO_LABEL` / the mockup.
 */
enum class GeoFilter(val key: String, val label: String) {
    ALL("all", "Tutti"),
    US("us", "🇺🇸 USA"),
    IT("it", "🇮🇹 ITA"),
    ASIA("asia", "🇯🇵 ASIA"),
}

/**
 * How many rows qualify per chip for the CURRENT sort.
 *
 * This is the size of the qualifying POOL, not the length of the list on screen:
 * the board renders only the top [GetMomentumLeadersUseCase.DEFAULT_LIMIT]. So
 * "ASIA 22" means "22 Asian names pass the gate; you are looking at the best 10
 * of them". Counting the truncated list instead would just print the limit back
 * (10 · 10 · 10 · 10) and carry no information.
 *
 * By construction [usa] + [ita] + [asia] == [total] (see [GeoFilter]).
 */
data class GeoCounts(
    val total: Int = 0,
    val usa: Int = 0,
    val ita: Int = 0,
    val asia: Int = 0,
) {
    operator fun get(filter: GeoFilter): Int = when (filter) {
        GeoFilter.ALL -> total
        GeoFilter.US -> usa
        GeoFilter.IT -> ita
        GeoFilter.ASIA -> asia
    }
}
