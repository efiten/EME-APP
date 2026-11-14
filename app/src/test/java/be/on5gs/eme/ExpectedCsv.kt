package be.on5gs.eme

/** Reads reference values shared with the web tests from `expected/<name>.csv` on the test classpath. */
object ExpectedCsv {
    fun read(name: String): List<Map<String, String>> {
        val text = ExpectedCsv::class.java.classLoader!!.getResource("expected/$name.csv")!!.readText()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(",").map { it.trim() }
        return lines.drop(1).map { line ->
            val cells = line.split(",").map { it.trim() }
            header.indices.associate { header[it] to cells.getOrElse(it) { "" } }
        }
    }
}
