package fixture.specnames

/** A spec name is a sentence, so it matches prose about the code better than the code does. */
class TotalsSpec {
    fun `combine numbers together and get a wrong total`() = Totals().combineNumbersTogether(listOf(1))
}
