package ua.org.tenletters.sibot;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

public class SIBot {

        private static final Logger log = LogManager.getLogger("SIBot");

        private static final int THREADS_COUNT = 8;
  
        private static final int CACHE_MIN_SAFE_SIZE = 10;
        private static final int CACHE_UPDATE_PORTION = 25;

        private static final String endpoint = "https://api.telegram.org/bot";
  
        private final String token;
        private final ExecutorService executor;
  
        private volatile boolean isStopped = false;
      
        private final Queue<String> cache = new ConcurrentLinkedQueue<>();
        private volatile boolean cacheIsLocked = false;
  
        private volatile int counter = 0;

        public SIBot(final String token) {
            this.token = token;
            this.executor = Executors.newFixedThreadPool(THREADS_COUNT, new DaemonThreadFactory());
            log.debug("[SIBOT] Created Bot");
        }
  
        public boolean isAlive() {
            return !isStopped;
        }
  
        public int getCacheSize() {
            return cache.size();
        }

        public int getCounter() {
            return counter;
        }

        public HttpResponse<JsonNode> sendMessage(final Integer chatId, final String text) throws UnirestException {
            log.debug("[SIBOT] Sending message");
            return Unirest.post(endpoint + token + "/sendMessage").field("chat_id", chatId).field("text", text)
              .field("reply_markup", "{\"keyboard\":[[{\"text\":\"Получить тему (/ask)\"}]],\"resize_keyboard\":true}")
                    .asJson();
        }
  
        public HttpResponse<JsonNode> answerInline(final String inlineId) throws UnirestException {
            log.debug("[SIBOT] Answering on inlined request");
            return Unirest.post(endpoint + token + "/answerInlineQuery").field("inline_query_id", inlineId)
                .field("results", "[]").field("switch_pm_text", "Получить тему")
                    .asJson();
        }

        public HttpResponse<JsonNode> getUpdates(final Integer offset) throws UnirestException {
            return Unirest.post(endpoint + token + "/getUpdates").field("offset", offset).asJson();
        }

        public void stop() {
            isStopped = true;
            if (executor != null) {
                executor.shutdown();
            }
        }

        public void start() {
            log.debug("[SIBOT] Starting loop");
            int last_update_id = 0;

            try {
                enableSSLSocket();
            } catch (Exception e) {
                log.error("[SIBOT] Can not enable SSL Socket!", e);
            }

            HttpResponse<JsonNode> response;
            while (!isStopped) {
                response = null;
                try {
                    response = getUpdates(last_update_id++);
                } catch (UnirestException e) {
                    log.error("[SIBOT] Can not get updates!", e);
                }

                if (response != null && response.getStatus() == 200) {
                    final JSONArray responses = response.getBody().getObject().getJSONArray("result");
                    if (responses.isNull(0)) {
                        continue;
                    } else {
                        last_update_id = responses.getJSONObject(responses.length() - 1).getInt("update_id") + 1;
                    }

                    log.debug("[SIBOT] Got something");
                    for (int i = 0; i < responses.length(); ++i) {
                        final JSONObject message = responses.getJSONObject(i).optJSONObject("message");
                        final JSONObject inline = responses.getJSONObject(i).optJSONObject("inline_query");
                        
                        if (message != null) {
                            final int chatId = message.getJSONObject("chat").getInt("id");
                            final String text = message.optString("text", "");

                            if (!text.isEmpty()) {
                                executor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (text.contains("/start")) {
                                            onStartCommand(chatId);
                                        } else if (text.contains("/ask")) {
                                            onAskCommand(chatId);
                                        }
                                    }
                                });
                            }
                        }
                      
                        if (inline != null) {
                            final String inlineId = inline.optString("id", "");
                            
                            if (!inlineId.isEmpty()) {
                                executor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        onInline(inlineId);
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }

        private void onStartCommand(final int chatId) {
            try {
                final String reply = "Версия бота: 1.2.1\n/ask - случайная тема свояка из базы db.chgk.info";
                sendMessage(chatId, reply);
            } catch (UnirestException e) {
                log.error("[SIBOT] Can not send question!", e);
            }
        }

        private void onInline(final String inlineId) {
            try {
                answerInline(inlineId);
                ++counter;
                if (!cacheIsLocked && CACHE_MIN_SAFE_SIZE > cache.size()) {
                    updateCache(CACHE_UPDATE_PORTION);
                }
            } catch (IOException e) {
                log.error("[SIBOT] Can not get question!", e);
            } catch (UnirestException e) {
                log.error("[SIBOT] Can not send question!", e);
            }
        }

        private void onAskCommand(final int chatId) {
            try {
                sendMessage(chatId, cache.isEmpty() ? getRandomTheme() : cache.poll());
                ++counter;
                if (!cacheIsLocked && CACHE_MIN_SAFE_SIZE > cache.size()) {
                    updateCache(CACHE_UPDATE_PORTION);
                }
            } catch (IOException e) {
                log.error("[SIBOT] Can not get question!", e);
            } catch (UnirestException e) {
                log.error("[SIBOT] Can not send question!", e);
            }
        }

        private String getRandomTheme() throws IOException {
            final Document doc = Jsoup.connect("https://db.chgk.info/random/from_2000-01-01/types5/limit1")
                    .ignoreHttpErrors(true)
                    .validateTLSCertificates(true)
                    .get();
            final Elements questions = doc.getElementsByClass("random_question");
            final StringBuilder message = new StringBuilder();
            if (questions.isEmpty()) {
                log.warn("[SIBOT] Can not parse question!");
            } else {
                message.append(Jsoup.clean(questions.get(0).toString(), "", Whitelist.none(),
                        new Document.OutputSettings().prettyPrint(false)).replace("&nbsp;", ""));
            }
            return message.toString();
        }

        private synchronized void updateCache(final int themesToLoad) throws IOException {
            cacheIsLocked = true;
            final Document doc = Jsoup.connect("https://db.chgk.info/random/from_2000-01-01/types5/limit" + themesToLoad)
			        .ignoreHttpErrors(true)
                    .validateTLSCertificates(true)
                    .get()
            final Elements questions = doc.getElementsByClass("random_question");
            if (questions.isEmpty()) {
                log.warn("[SIBOT] Can not parse question!");
            } else {
                final List<String> temp = new LinkedList<>();
                for (Element question : questions) {
                    temp.add(Jsoup.clean(question.toString(), "", Whitelist.none(),
                            new Document.OutputSettings().prettyPrint(false)).replace("&nbsp;", "").trim());
                }
                cache.addAll(temp);
            }
            cacheIsLocked = false;
        }
        
        public static void enableSSLSocket() throws KeyManagementException, NoSuchAlgorithmException {
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
     
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }
 
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }
 
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        }
}
