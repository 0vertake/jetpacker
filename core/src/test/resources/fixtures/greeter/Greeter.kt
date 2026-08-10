package fixture

interface Greeter {
    fun greet(name: String): String
}

class FormalGreeter : Greeter {
    override fun greet(name: String): String = "Good day, $name"
}

class CasualGreeter : Greeter {
    override fun greet(name: String): String = "hey $name"
}
