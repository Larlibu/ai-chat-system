package de.larlibu.aichat;

import de.larlibu.aichat.controller.AiController;
import de.larlibu.aichat.controller.ChatController;
import de.larlibu.aichat.factory.AppFactory;
import de.larlibu.aichat.network.SocketServer;
import de.larlibu.aichat.view.CliView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * CLI-Einstiegspunkt der Anwendung.
 *
 * <p>Die App kennt drei Modi:
 * <ul>
 *   <li>{@code server}: Startet den Chat-Server (TCP Listener).</li>
 *   <li>{@code human <host> <port>}: Startet einen interaktiven Konsolen-Client.</li>
 *   <li>{@code ai [personaId|?] <host> <port>}: Startet einen Bot-Client mit Persona oder interaktiver Auswahl.</li>
 * </ul>
 *
 * <p>Das Bauen/Packen erfolgt via Maven Shade Plugin; die Main-Class ist im Manifest eingetragen.
 */
public final class AiChatMain {
    private static final Logger log = LogManager.getLogger(AiChatMain.class);

    /**
     * Parst die CLI-Argumente und startet den gewuenschten Modus.
     *
     * @param args {@code server} | {@code human host port} | {@code ai [persona|?] host port}
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            printUsageAndExit();
            return;
        }

        AppFactory factory = new AppFactory();

        String mode = args[0].trim().toLowerCase();
        switch (mode) {
            case "server" -> runServer(factory);
            case "human" -> runHuman(factory, args);
            case "ai" -> runAi(factory, args);
            default -> printUsageAndExit();
        }
    }

    private static void runServer(AppFactory factory) {
        log.info("Starting server...");
        SocketServer server = factory.createServer();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown requested. Stopping server...");
                server.stopSoft("Server shutdown. Bye.");
            } catch (Exception ignored) {
            }
        }, "shutdown-hook-server"));

        server.startBlocking();
    }

    private static void runHuman(AppFactory factory, String[] args) {
        if (args.length != 3) {
            printUsageAndExit();
            return;
        }
        String host = args[1];
        int port = parsePortOrExit(args[2]);

        log.info("Starting human client ({}:{})", host, port);
        ChatController controller = factory.createHumanClient(host, port);
        controller.runBlocking();
    }

    private static void runAi(AppFactory factory, String[] args) {
        if (args.length != 3 && args.length != 4) {
            printUsageAndExit();
            return;
        }

        String personaId;
        String host;
        int port;
        if (args.length == 4) {
            personaId = args[1];
            host = args[2];
            port = parsePortOrExit(args[3]);
        } else {
            host = args[1];
            port = parsePortOrExit(args[2]);
            personaId = promptPersonaSelection(factory);
            if (personaId == null) {
                log.warn("AI client start aborted: no persona selected");
                return;
            }
        }

        log.info("Starting AI client persona='{}' ({}:{})", personaId, host, port);
        AiController controller = factory.createAiClient(personaId, host, port);
        controller.runBlocking();
    }

    /**
     * Fragt interaktiv eine Persona fuer den AI-Client ab.
     *
     * @param factory App-Factory fuer View- und Persona-Zugriff
     * @return Persona-ID oder {@code null} bei EOF/Abbruch
     */
    private static String promptPersonaSelection(AppFactory factory) {
        CliView view = factory.createCliView();
        List<de.larlibu.aichat.ai.AiPersonaFactory.PersonaOption> options = factory.createPersonaFactory().availableOptions();

        view.system("Choose an AI persona:");
        for (int i = 0; i < options.size(); i++) {
            var option = options.get(i);
            view.system("  " + (i + 1) + ". " + option.displayName() + " [" + option.id() + "] - " + option.description());
        }

        while (true) {
            String input = view.readLine("Persona (number/id, ? for Mystery Guest): ");
            if (input == null) return null;

            String choice = input.trim();
            if (choice.isEmpty()) {
                view.system("Please choose one of the listed personas.");
                continue;
            }

            for (int i = 0; i < options.size(); i++) {
                var option = options.get(i);
                if (choice.equals(String.valueOf(i + 1)) || choice.equalsIgnoreCase(option.id())) {
                    return option.id();
                }
            }

            view.system("Unknown selection: " + choice);
        }
    }

    /**
     * Parst einen TCP-Port oder beendet das Programm mit Exit-Code 2.
     *
     * @param s Port als String
     * @return Port im Bereich 1..65535
     */
    private static int parsePortOrExit(String s) {
        try {
            int p = Integer.parseInt(s);
            if (p < 1 || p > 65535) throw new IllegalArgumentException("port");
            return p;
        } catch (Exception e) {
            log.error("Invalid port: {}", s);
            System.exit(2);
            return -1;
        }
    }

    /**
     * Druckt die Hilfe (inkl. Personas) und beendet das Programm mit Exit-Code 1.
     */
    private static void printUsageAndExit() {
        System.out.println("""
                Usage:
                  java -jar target/ai-chat-system-1.0.0.jar server
                  java -jar target/ai-chat-system-1.0.0.jar human <host> <port>
                  java -jar target/ai-chat-system-1.0.0.jar ai [persona|?] <host> <port>

                Personas:
                  tom_sawyer
                  albert_zweistein
                  ?

                Notes:
                  Omit <persona> in ai mode to choose interactively at startup.
                """);
        System.exit(1);
    }
}
