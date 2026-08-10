package fixture.initializers

fun build(): String = "built"

class Holder {
    val value: String = build()
}
