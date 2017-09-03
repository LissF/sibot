package ua.org.tenletters.bot.sibot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mashape.unirest.http.Unirest
import com.mashape.unirest.http.exceptions.UnirestException
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Whitelist
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager


@RestController
class BotController {

    companion object {
        val API_ENDPOINT = "https://api.telegram.org/bot"
        val DB_ENDPOINT = "https://db.chgk.info/random/from_2000-01-01/types5/limit1"

        val CACHE_MIN_SAFE_SIZE = 10
        val CACHE_UPDATE_PORTION = 25
    }

    init {
        enableSSLSocket()
    }

    val logger: Logger = Logger.getLogger("[SIBOT]")

    @Value("\${token}")
    lateinit var token: String

    private val cache = ConcurrentLinkedQueue<String>()
    @Volatile private var cacheIsLocked = false

    @PostMapping("/\${token}")
    fun onUpdate(@RequestBody update: Update) {
        logger.log(Level.INFO, "Got update: " + jacksonObjectMapper().writeValueAsString(update))

        if (update.message != null) {
            val chatId = update.message.chat.id
            val text = update.message.text

            launch(CommonPool) {
                when {
                    text?.contains("/start") == true -> onStartCommand(chatId)
                    text?.contains("/ask") == true -> onAskCommand(chatId)
                }
            }
        }
    }

    private suspend fun onStartCommand(chatId: Long) {
        try {
            sendMessage(chatId, "Версия бота: 1.3.0\n/ask - случайная тема свояка из базы db.chgk.info")
        } catch (e: UnirestException) {
            logger.log(Level.SEVERE, "Can not send question!", e)
        }
    }

    private suspend fun onAskCommand(chatId: Long) {
        try {
            sendMessage(chatId, if (cache.isEmpty()) getRandomTheme() else cache.poll())
            if (!cacheIsLocked && CACHE_MIN_SAFE_SIZE > cache.size) {
                updateCache(CACHE_UPDATE_PORTION)
            }
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "Can not get question!", e)
        } catch (e: UnirestException) {
            logger.log(Level.SEVERE, "Can not send question!", e)
        }
    }

    @Throws(UnirestException::class)
    private fun sendMessage(chatId: Long, text: String) {
        logger.log(Level.INFO, "Sending message")
        Unirest.post(API_ENDPOINT + token + "/sendMessage")
                .field("chat_id", chatId)
                .field("text", text)
                .field("reply_markup", ReplyKeyboardMarkup(arrayOf(arrayOf(KeyboardButton("Получить тему (/ask)"))), true))
                .asJson()
    }

    @Throws(IOException::class)
    private fun getRandomTheme(): String {
        val doc = Jsoup.connect(DB_ENDPOINT)
                .ignoreHttpErrors(true)
                .validateTLSCertificates(true)
                .get()
        val questions = doc.getElementsByClass("random_question")
        val message = StringBuilder()
        if (questions.isEmpty()) {
            logger.log(Level.WARNING, "Can not parse question!")
        } else {
            message.append(clean(questions[0].toString()))
        }
        return message.toString()
    }

    @Throws(IOException::class)
    private fun updateCache(themesToLoad: Int) {
        synchronized(cache) {
            cacheIsLocked = true
            val doc = Jsoup.connect(DB_ENDPOINT + themesToLoad)
                    .ignoreHttpErrors(true)
                    .validateTLSCertificates(true)
                    .get()
            val questions = doc.getElementsByClass("random_question")
            if (questions.isEmpty()) {
                logger.log(Level.WARNING, "Can not parse question!")
            } else {
                cache.addAll(questions.mapTo(LinkedList()) {
                    clean(it.toString())
                })
            }
            cacheIsLocked = false
        }
    }

    private fun clean(question: String) = Jsoup.clean(question, "", Whitelist.none(),
            Document.OutputSettings().prettyPrint(false)).replace("&nbsp;", "").trim()

    @Throws(KeyManagementException::class, NoSuchAlgorithmException::class)
    private fun enableSSLSocket() {
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)

            @Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            }


        }), SecureRandom())

        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }
}
