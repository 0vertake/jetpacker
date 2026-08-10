package fixture

class GreetingService(private val greeter: Greeter) {
    fun welcome(name: String): String = greeter.greet(name)
}
