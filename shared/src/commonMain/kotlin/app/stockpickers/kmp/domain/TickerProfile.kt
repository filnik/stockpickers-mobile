package app.stockpickers.kmp.domain

/**
 * How old the UPSTREAM CONTENT is, against the TTL the pipeline published with it.
 *
 * This is NOT the age of the local cache — those are two different clocks and must
 * not be confused. This one only drives a badge ("this text may be out of date");
 * the cache's own age drives refetching, and lives in the repository.
 *
 * [UNKNOWN] is a real answer, not a fallback for [FRESH]: a block with no (or an
 * unparseable) timestamp cannot be shown as current. Same fail-safe rule as
 * [QualityGate.passesFilters] — never claim a verdict you cannot prove.
 */
enum class ContentFreshness { FRESH, STALE, UNKNOWN }

/**
 * The written profile of one ticker: what the company is, the case for and against
 * it, and when it next reports. Generated upstream by the research pipeline and read
 * here verbatim — this app never composes or edits it.
 *
 * Comes from a DIFFERENT Supabase table than [TickerDetail] (`descriptions_cache`,
 * not `scanner_cache`), is fetched per-ticker on demand, and is ABSENT for most
 * symbols: coverage follows the pipeline's pool. A null profile is the normal case,
 * not an error.
 *
 * The text is always Italian regardless of device locale — the pipeline generates it
 * that way and the row carries no language field. The UI says so explicitly rather
 * than letting an English-locale reader think something broke.
 *
 * NOTE: no field is called `description`, deliberately. `NSObject` already exposes
 * that name, so a property spelled exactly that way risks being mangled on the
 * Obj-C bridge — the same family of problem as the `init*` prefix ban.
 */
data class TickerProfile(
    val ticker: String,
    /** What the company does. Slow-changing, long TTL. */
    val timelessDescription: String?,
    /** Where the company stands right now. Short TTL, so it goes stale first. */
    val currentDescription: String?,
    val pros: List<String>,
    val cons: List<String>,
    val nextEarnings: NextEarnings?,
    val timelessFreshness: ContentFreshness,
    val currentFreshness: ContentFreshness,
) {
    /**
     * The single badge the card shows. Pessimistic on purpose: one stale block makes
     * the whole card stale, because the reader has no way to tell which sentence came
     * from which block.
     */
    val freshness: ContentFreshness
        get() = when {
            timelessFreshness == ContentFreshness.STALE ||
                currentFreshness == ContentFreshness.STALE -> ContentFreshness.STALE

            timelessFreshness == ContentFreshness.FRESH ||
                currentFreshness == ContentFreshness.FRESH -> ContentFreshness.FRESH

            else -> ContentFreshness.UNKNOWN
        }
}

/**
 * The company's next scheduled results.
 *
 * [daysAway] is RECOMPUTED locally from [date], not read from upstream. The pipeline
 * stores a countdown that was correct the day it ran; with a week-long TTL (and a
 * cache that can be read offline for longer) replaying it verbatim would show a
 * number that silently drifts further from the truth every day. Upstream's own value
 * stands in only when [date] is present but unparseable; when there is no date at all
 * it is null, because an unanchored countdown cannot be sanity-checked by anyone.
 *
 * [date] stays the raw ISO string the pipeline published: formatting is the UI's job,
 * as with [TickerDetail.updatedAt].
 */
data class NextEarnings(
    val date: String?,
    val daysAway: Int?,
    /**
     * What to watch at the next report, as published. DESPITE THE NAME this is not a
     * rating: upstream writes a free-text note, routinely a couple of hundred
     * characters ("Focus su: traiettoria del NII dopo il rialzo BCE…"). It is prose and
     * must be laid out as prose — never inlined next to the date, and never parsed.
     */
    val consensus: String?,
)
