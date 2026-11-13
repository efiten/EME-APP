package be.on5gs.eme.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Nagebootste HB9Q-logger op 127.0.0.1, voor tests zonder netwerk.
 * Gedraagt zich zoals Hb9qClient de echte site ziet:
 *  - GET /        zonder ingelogde sessie: loginformulier met verborgen veld "swordfisch" (en een sessiecookie)
 *                 met ingelogde sessie: pagina met "srv=logout" en GEEN loginformulier (zoals hb9q_page.html)
 *  - POST /       juiste login: pagina met "srv=logout"; anders weer het loginformulier
 *  - POST /rpc/logger/logger_lst.cfm   ingelogd: JSON; anders een redirect naar /
 *  - GET /?srv=logout   sessie uitloggen
 */
class FakeHb9q(private val users: Map<String, String>) : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val sessions = mutableMapOf<String, String?>()   // sessie-id -> ingelogde gebruiker
    private var nextSession = 1

    /** Aantal login-pogingen (POST van het loginformulier). */
    var loginPosts = 0
        private set

    /** Laatste gebruiker die met succes inlogde. */
    var lastLoggedIn: String? = null
        private set

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex -> ex.use { handle(it) } }
        server.start()
    }

    override fun close() = server.stop(0)

    @Synchronized
    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val sid = ex.requestHeaders["Cookie"].orEmpty().joinToString(";")
            .let { Regex("""CFID=(\w+)""").find(it)?.groupValues?.get(1) }
            ?.takeIf { it in sessions }
        val user = sid?.let { sessions[it] }
        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery.orEmpty()

        when {
            path == "/" && ex.requestMethod == "GET" && query == "srv=logout" -> {
                if (sid != null) sessions[sid] = null
                send(ex, 200, LOGIN_PAGE)
            }
            path == "/" && ex.requestMethod == "GET" -> {
                if (user != null) {
                    send(ex, 200, LOGGED_IN_PAGE)
                } else {
                    if (sid == null) {
                        val id = "S${nextSession++}"
                        sessions[id] = null
                        ex.responseHeaders.add("Set-Cookie", "CFID=$id; Path=/")
                    }
                    send(ex, 200, LOGIN_PAGE)
                }
            }
            path == "/" && ex.requestMethod == "POST" -> {
                loginPosts++
                val form = parseForm(body)
                val name = form["txtLoginName"]
                val ok = sid != null && form["swordfisch"] == TOKEN && name != null && users[name] == form["txtPwd"]
                if (ok) {
                    sessions[sid!!] = name
                    lastLoggedIn = name
                    send(ex, 200, LOGGED_IN_PAGE)
                } else {
                    send(ex, 200, LOGIN_PAGE)
                }
            }
            path == "/rpc/logger/logger_lst.cfm" -> {
                if (user != null) {
                    send(ex, 200, """{"status":"success","logger":""}""")
                } else {
                    ex.responseHeaders.add("Location", "/")
                    send(ex, 302, "")
                }
            }
            else -> send(ex, 404, "")
        }
    }

    private fun send(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { "=" in it }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    companion object {
        const val TOKEN = "fish123"
        val LOGIN_PAGE = """<html><form method="post"><input type="hidden" name="swordfisch" value="$TOKEN">
            <input name="txtLoginName"><input name="txtPwd" type="password"></form></html>"""
        const val LOGGED_IN_PAGE = """<html><a href="/?srv=logout">Logout</a></html>"""
    }
}
