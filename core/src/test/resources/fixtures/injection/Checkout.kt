package fixture.injection

/** Names deliberately share no vocabulary with the implementation, so only the graph connects them. */
interface PaymentGateway {
    fun charge(cents: Int): Boolean
}

class StripeAdapter : PaymentGateway {
    override fun charge(cents: Int): Boolean = cents > 0
}

class Checkout(private val gateway: PaymentGateway) {
    fun buy(cents: Int): Boolean = gateway.charge(cents)
}
