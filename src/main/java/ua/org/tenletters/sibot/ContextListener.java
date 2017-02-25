package ua.org.tenletters.sibot;

import ua.org.tenletters.sibot.SIBot;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ContextListener implements ServletContextListener {

    private static final Logger log = LogManager.getLogger("ContextListener");

    private BotThread botThread = null;

    public void contextInitialized(ServletContextEvent sce) {
        if ((botThread == null) || (!botThread.isAlive())) {
            final SIBot sibot = new SIBot("133372591:AAHWbe8g0m6Dwxz7UZLC9DkQHM9WGSjXOZ8");
            botThread = new BotThread(sibot);
            botThread.start();
            BotServlet.setBot(sce.getServletContext(), sibot);
        }
    }

    public void contextDestroyed(ServletContextEvent sce){
        try {
            if (botThread != null && botThread.isAlive()) {
                botThread.interrupt();
            }
        } catch (Exception ex) {
            log.error("[SIBOT] Can not stop Bot!", ex);
        }
    }
  
    private static final class BotThread extends Thread {
        
        private final SIBot sibot;
      
        private BotThread(final SIBot sibot) {
            super();
            this.sibot = sibot;
        }
      
        public void run() {
            this.sibot.start();
        }
      
        public void interrupt() {
            this.sibot.stop();
            super.interrupt();
        }
    }
}
