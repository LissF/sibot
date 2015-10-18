package ua.org.tenletters.sibot;


import ua.org.tenletters.sibot.SIBot;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public final class BotServlet extends HttpServlet {
    private static final long serialVersionUID = 7L;
    private static final String ATT_BOT = "bot";

    static void setBot(final ServletContext context, final SIBot bot) {
        context.setAttribute(ATT_BOT, bot);
    }
  
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        final PrintWriter out = response.getWriter();
        final SIBot bot = (SIBot) getServletContext().getAttribute(ATT_BOT);
        out.println("Bot is " + (bot.isAlive() ? "alive" : "stopped"));
        out.println("Bot has showed " + bot.getCounter() + " themes");
        out.println("Bot has " + bot.getCacheSize() + " themes cached");
    }
}
