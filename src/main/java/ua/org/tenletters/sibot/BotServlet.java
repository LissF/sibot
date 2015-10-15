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

    public void destroy() {
        // stop
    }

    private void startBot() {
        botThread = new Thread(new SIBot("133372591:AAHWbe8g0m6Dwxz7UZLC9DkQHM9WGSjXOZ8"));
        botThread.start();
    }

    private final class SIBot implements Runnable {
        private static final int MAX_ERROR_REPEAT = 3;

        private final String endpoint = "https://api.telegram.org/bot";
        private final String token;

        private String lastError = "";
        private int errorCounter = 0;

        public SIBot(String token) {
            this.token = token;
            System.out.println("[TELEGRAM] Created Bot");
        }

        public HttpResponse<JsonNode> sendMessage(Integer chatId, String text) throws UnirestException {
            System.out.println("[TELEGRAM] Sending message");
            return Unirest.post(endpoint + token + "/sendMessage").field("chat_id", chatId).field("text", text)
                    .asJson();
        }

        public HttpResponse<JsonNode> getUpdates(Integer offset) throws UnirestException {
            return Unirest.post(endpoint + token + "/getUpdates").field("offset", offset).asJson();
        }

        public void run() {
            try {
                runBotLoop();
            } catch (Exception e) {
                errorMessageOnFace = e.toString();

                System.out.println("[TELEGRAM] Error: " + errorMessageOnFace);
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
            System.out.println("[TELEGRAM] Starting loop");
            int last_update_id = 0; // last processed command

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

                    System.out.println("[TELEGRAM] Got something");
                    for (int i = 0; i < responses.length(); ++i) {
                        final JSONObject message = responses.getJSONObject(i).getJSONObject("message");
                        final int chatId = message.getJSONObject("chat").getInt("id");
                        final String text = message.optString("text", "");

                        if (text.contains("/start")) {
                            final String reply = "Версия бота: 1.0\n/ask - случайная тема свояка из базы db.chgk.info";
                            sendMessage(chatId, reply);
                        } else if (text.contains("/ask")) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    sendRandomQuestions(chatId, 1);
                                }
                            }).start();
                        }
                    }
                }
            }
        }

        private void sendRandomQuestions(final int chatId, final int amount) {
            try {
                final Document doc = Jsoup.connect("http://db.chgk.info/random/from_2000-01-01/answers/types5/limit"
                        + amount).get();
                final Elements questions = doc.getElementsByClass("random_question");
                for (Element question : questions) {
                    sendMessage(
                            chatId,
                            Jsoup.clean(question.toString(), "", Whitelist.none(),
                                    new Document.OutputSettings().prettyPrint(false)).replace("&nbsp;", "")
                    );
                }
            } catch (IOException e) {
                System.out.println("[TELEGRAM] Can not get question! " + e.toString());
            } catch (UnirestException e) {
                System.out.println("[TELEGRAM] Can not send question! " + e.toString());
            }
        }
    }
}
