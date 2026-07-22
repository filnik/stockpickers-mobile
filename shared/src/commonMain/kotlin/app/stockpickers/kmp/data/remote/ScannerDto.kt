package app.stockpickers.kmp.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of `scanner_cache`, flattened by the PostgREST `select` (the `data`
 * JSONB fields are aliased to top level, e.g. `clenow:data->clenow`).
 *
 * Everything except `ticker` is nullable: the Python pipeline publishes rows in
 * varying states of completeness and the client must not crash on a partial row.
 */
@Serializable
data class TickerDto(
    val ticker: String,
    val name: String? = null,
    val country: String? = null,
    val sector: String? = null,
    @SerialName("price_eur") val priceEur: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val clenow: Double? = null,
    @SerialName("mom_1m") val mom1m: Double? = null,
    @SerialName("mom_2m") val mom2m: Double? = null,
    @SerialName("mom_3m") val mom3m: Double? = null,
    @SerialName("mom_12m") val mom12m: Double? = null,
    /**
     * Clenow's annualised regression return, used as the [mom12m] fallback exactly
     * as upstream's own writer and web client do.
     *
     * It is a DECIMAL FRACTION, like every other `mom_*`. Note that
     * `tech-docs/reference/scanner_cache.md` upstream says it is in percent units —
     * that doc is wrong, and following it divides every fallback value by 100. The
     * authority is `services/supabase_writer.py`, whose comment records the incident
     * (2026-06-10) where a `/100` made a +45% trend render as "+0,4%".
     */
    @SerialName("ann_mom") val annMom: Double? = null,
    @SerialName("forward_pe") val forwardPe: Double? = null,
    val peg: Double? = null,
    val roic: Double? = null,
    val r2: Double? = null,
    /** Python's quality verdict. Absent / `{}` on un-evaluated rows. */
    @SerialName("quality_gate") val qualityGate: QualityGateDto? = null,
    /** Absent on pre-fix cache payloads — treat absence as "not markdown". */
    @SerialName("wyckoff_markdown") val wyckoffMarkdown: Boolean? = null,
    /** Non-empty string => this row duplicates another listing (ADR vs native). */
    @SerialName("duplicate_of") val duplicateOf: String? = null,
)

@Serializable
data class QualityGateDto(
    @SerialName("passes_filters") val passesFilters: Boolean? = null,
    val reason: String? = null,
    @SerialName("failed_filter") val failedFilter: String? = null,
)
