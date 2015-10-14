package ua.org.tenletters.sibot;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Servlet implementation class FileCounter
 */

@WebServlet("/Server")
public class BotServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;

    private Thread bot;

  @Override
  protected void doGet(HttpServletRequest request,
      HttpServletResponse response) throws ServletException, IOException {
    final PrintWriter out = response.getWriter();
    out.println("Bot is " + (bot.isAlive() ? "alive!" : "dead!"));
    if (!bot.isAlive() || bot.isInterrupted()) {
      out.println("Restarting the bot");
      startBot();
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
    bot = new Thread(new SIBot("133372591:AAHWbe8g0m6Dwxz7UZLC9DkQHM9WGSjXOZ8"));
    bot.start();
  }
  
  private static final class SIBot implements Runnable {
        private final String endpoint = "https://api.telegram.org/bot";
        private final String token;
    
        private String lastError = "";

        public SIBot(String token) {
            this.token = token;
            System.out.println("[TELEGRAM] Created Bot");
        }

        public HttpResponse<JsonNode> sendMessage(Integer chatId, String text) throws UnirestException {
            System.out.println("[TELEGRAM] Sending message");
            return Unirest.post(endpoint + token + "/sendMessage").field("chat_id", chatId).field("text", text).asJson();
        }

        public HttpResponse<JsonNode> getUpdates(Integer offset) throws UnirestException {
            return Unirest.post(endpoint + token + "/getUpdates").field("offset", offset).asJson();
        }

        public void run() {
            try {
                runBotLoop();
            } catch (Exception e) {
                // TODO: log error
                System.out.println("[TELEGRAM] Error: " + e.toString());
              if (!lastError.equals(e.toString())) {
                run();
              }
              lastError = e.toString();
            }
        }

        public void runBotLoop() throws UnirestException {
            System.out.println("[TELEGRAM] Starting loop");
            int last_update_id = 0; // last processed command

            HttpResponse<JsonNode> response;
            while (true) {
                response = getUpdates(last_update_id++);

                if (response.getStatus() == 200) {
                    JSONArray responses = response.getBody().getObject().getJSONArray("result");
                    if (responses.isNull(0))
                        continue;
                    else
                        last_update_id = responses.getJSONObject(responses.length() - 1).getInt("update_id") + 1;

                    System.out.println("[TELEGRAM] Got something");
                    for (int i = 0; i < responses.length(); i++) {
                        JSONObject message = responses.getJSONObject(i).getJSONObject("message");

                        int chat_id = message.getJSONObject("chat").getInt("id");

                        String username = message.getJSONObject("chat").getString("username");

                        String text = message.optString("text", "");

                        if (text.contains("/start")) {
                            String reply =
                                           "Hi, this is an example bot\n" + "Your chat_id is " + chat_id + "\n" + "Your username is "
                                               + username;
                            sendMessage(chat_id, reply);
                        } else if (text.contains("/echo")) {
                            sendMessage(chat_id, "Received " + text);
                        } else if (text.contains("/toupper")) {
                            String param = text.substring("/toupper".length(), text.length());
                            sendMessage(chat_id, param.toUpperCase());
                        }
                    }
                }
            }
        }
    }

}