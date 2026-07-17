package app.stockpickers.kmp.base

/**
 * Test-data factories, ported from the Wishew testing convention
 * (`SingleModelCreator` / `MultipleModelsCreator`). A creator exposes a single,
 * fully-populated [model] and — for collections — a [list] whose items are
 * unique. Tests derive variants with `.model.copy(...)` rather than re-listing
 * every field, so a new constructor argument is a one-line change here.
 */
interface SingleModelCreator<T> {
    val model: T
}

interface MultipleModelsCreator<T : Any> : SingleModelCreator<T> {
    override val model: T

    fun list(count: Int = 2): List<T>
}
