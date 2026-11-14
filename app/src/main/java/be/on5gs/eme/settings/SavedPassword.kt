package be.on5gs.eme.settings

/** Het laatst bewaarde HB9Q-wachtwoord, zodat "Login opslaan" met een leeg wachtwoordveld het bewaarde houdt. */
internal class SavedPassword(private var value: String?) {

    /** Wat er bewaard wordt: het getypte wachtwoord, of het laatst bewaarde als het veld leeg is. */
    fun passwordToSave(typed: String): String = typed.ifEmpty { value ?: "" }.also { value = it }

    fun clear() {
        value = null
    }
}
