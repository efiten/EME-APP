package be.on5gs.eme

/**
 * De huidige tijd voor de schermen en de stores. Tests zetten een vaste tijd, zodat de maanpositie,
 * de Deg-waarden en de Live CQ-tijden bij elke run hetzelfde zijn (zie LayoutTest).
 */
object AppClock {
    @Volatile
    internal var nowMillis: () -> Long = System::currentTimeMillis

    fun now(): Long = nowMillis()
}
