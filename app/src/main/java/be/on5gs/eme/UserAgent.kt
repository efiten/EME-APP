package be.on5gs.eme

/** Eerlijke User-Agent voor HB9Q en W3SZ, altijd met de versie uit build.gradle.kts. */
object UserAgent {
    val VALUE = "EME-ON5GS-Android/${BuildConfig.VERSION_NAME}"
}
