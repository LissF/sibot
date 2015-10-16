package ua.org.tenletters.sibot;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.http.util.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class BotServlet extends HttpServlet {
    private static final long serialVersionUID = 5L;

    private Thread botThread;

    private String errorMessageOnFace = "";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        final PrintWriter out = response.getWriter();
        out.println("Bot is " + (botThread.isAlive() ? "alive!" : "dead!"));
        if (!botThread.isAlive() || botThread.isInterrupted()) {
            out.println("Restarting the bot");
            startBot();
        }
        if (!TextUtils.isEmpty(errorMessageOnFace)) {
            out.println("Last error: " + errorMessageOnFace);
        }
    }

    @Override
    public void init() throws ServletException {
        startBot();
    }

    private synchronized void startBot() {
        if (botThread == null || !botThread.isAlive() || botThread.isInterrupted()) {
            botThread = new Thread(new SIBot("133372591:AAHWbe8g0m6Dwxz7UZLC9DkQHM9WGSjXOZ8"));
            botThread.start();
        }
    }

    final class SIBot implements Runnable {
        private static final int MAX_ERROR_REPEAT = 3;

        private static final int CACHE_MIN_SAFE_SIZE = 10;
        private static final int CACHE_UPDATE_PORTION = 25;

        private final String endpoint = "https://api.telegram.org/bot";
        private final String token;

        private final Queue<String> cache = new ConcurrentLinkedQueue<>();
        private volatile boolean cacheIsLocked = false;

        private String lastError = "";
        private int errorCounter = 0;

        public SIBot(final String token) {
            this.token = token;
            System.out.println("[SIBOT] Created Bot");
        }

        public HttpResponse<JsonNode> sendMessage(final Integer chatId, final String text) throws UnirestException {
            System.out.println("[SIBOT] Sending message");
            return Unirest.post(endpoint + token + "/sendMessage").field("chat_id", chatId).field("text", text)
                    .asJson();
        }

        public HttpResponse<JsonNode> getUpdates(final Integer offset) throws UnirestException {
            return Unirest.post(endpoint + token + "/getUpdates").field("offset", offset).asJson();
        }

        public void run() {
            try {
                runBotLoop();
            } catch (Exception e) {
                errorMessageOnFace = e.toString();

                System.out.println("[SIBOT] Error: " + errorMessageOnFace);
                if (MAX_ERROR_REPEAT < errorCounter) {
                    if (lastError.equals(errorMessageOnFace)) {
                        ++errorCounter;
                    } else {
                        lastError = errorMessageOnFace;
                        errorCounter = 0;
                    }
                    run();
                }
            }
        }

        public void runBotLoop() throws UnirestException {
            System.out.println("[SIBOT] Starting loop");
            int last_update_id = 0;

            HttpResponse<JsonNode> response;
            while (true) {
                response = getUpdates(last_update_id++);

                if (response.getStatus() == 200) {
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
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    if (text.contains("/start")) {
                                        onStartCommand(chatId);
                                    } else if (text.contains("/ask")) {
                                        onAskCommand(chatId);
                                    }
                                }
                            }).start();
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
}
