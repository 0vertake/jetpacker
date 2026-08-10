package fixture.overloads

class Formatter {
    fun format(value: Int): String = "int:$value"

    fun format(value: String): String = "str:$value"
}

fun useInt(formatter: Formatter): String = formatter.format(1)

fun useString(formatter: Formatter): String = formatter.format("a")
