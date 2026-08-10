package fixture.shadowing

class Repository {
    fun find(id: String): String = "member"
}

/** Never called: Kotlin resolves members before extensions. */
fun Repository.find(id: String): String = "extension"

fun lookup(repository: Repository): String = repository.find("id")
