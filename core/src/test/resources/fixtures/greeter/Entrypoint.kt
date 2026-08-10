package fixture

import fixture.GreetingService as Service

fun run(): String {
    val service = Service(FormalGreeter())
    return service.welcome("world")
}
