package fixture.specnames

/**
 * Adds up numbers.
 *
 * Documented the way a detekt rule documents itself, with an example long enough to cost more
 * tokens than the declaration it describes:
 *
 * <noncompliant>
 * val total = values.fold(0) { accumulator, value -> accumulator + value }
 * </noncompliant>
 */
class Totals {
    fun combineNumbersTogether(values: List<Int>): Int = values.sum()
}
