package ua.org.tenletters.sibot;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SIBot {

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
            System.out.println("[SIBOT] Created Bot");
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
            System.out.println("[SIBOT] Sending message");
            return Unirest.post(endpoint + token + "/sendMessage").field("chat_id", chatId).field("text", text)
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
            System.out.println("[SIBOT] Starting loop");
            int last_update_id = 0;

            HttpResponse<JsonNode> response;
            while (!isStopped) {
                response = null;
                try {
                    response = getUpdates(last_update_id++);
                } catch (UnirestException e) {
                    System.out.println("[SIBOT] Can not get updates: " + e);
                    // TODO: wait some time before repeat
                }

                if (response != null && response.getStatus() == 200) {
                    final JSONArray responses = response.getBody().getObject().getJSONArray("result");
                    if (responses.isNull(0)) {
                        continue;
                    } else {
                        last_update_id = responses.getJSONObject(responses.length() - 1).getInt("update_id") + 1;
                    }

                    System.out.println("[SIBOT] Got something");
                    for (int i = 0; i < responses.length(); ++i) {
                        final JSONObject message = responses.getJSONObject(i).optJSONObject("message");
                        if (message == null) {
                            continue;
                        }
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
                }
            }
        }

        private void onStartCommand(final int chatId) {
            try {
                final String reply = "Версия бота: 1.0\n/ask - случайная тема свояка из базы db.chgk.info";
                sendMessage(chatId, reply);
            } catch (UnirestException e) {
                System.out.println("[SIBOT] Can not send question! " + e.toString());
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
                System.out.println("[SIBOT] Can not get question! " + e.toString());
            } catch (UnirestException e) {
                System.out.println("[SIBOT] Can not send question! " + e.toString());
            }
        }

        private String getRandomTheme() throws IOException {
            final Document doc = Jsoup.connect("http://db.chgk.info/random/from_2000-01-01/types5/limit1").get();
            final Elements questions = doc.getElementsByClass("random_question");
            final StringBuilder message = new StringBuilder();
            if (questions.isEmpty()) {
                System.out.println("[SIBOT] Can not parse question!");
            } else {
                message.append(Jsoup.clean(questions.get(0).toString(), "", Whitelist.none(),
                        new Document.OutputSettings().prettyPrint(false)).replace("&nbsp;", ""));
            }
            return message.toString();
        }

        private synchronized void updateCache(final int themesToLoad) throws IOException {
            cacheIsLocked = true;
            final Document doc = Jsoup.connect("http://db.chgk.info/random/from_2000-01-01/types5/limit"
                    + themesToLoad).get();
            final Elements questions = doc.getElementsByClass("random_question");
            if (questions.isEmpty()) {
                System.out.println("[SIBOT] Can not parse question!");
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
}
