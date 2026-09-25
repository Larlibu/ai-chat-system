package de.larlibu.aichat.controller;

import de.larlibu.aichat.ai.AIResponder;
import de.larlibu.aichat.ai.AiPersonaFactory;
import de.larlibu.aichat.model.ChatHistory;
import de.larlibu.aichat.model.ChatMessage;
import de.larlibu.aichat.model.Persona;
import de.larlibu.aichat.network.SocketClient;
import de.larlibu.aichat.view.CliView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot-Client, der als Persona im Chatraum mitliest und auf User-Nachrichten antwortet.
 *
 * <p>Ablauf:
 * <ol>
 *   <li>Persona anhand der Persona-ID erzeugen</li>
 *   <li>Zum Server verbinden und Nickname setzen</li>
 *   <li>Eingehende Nachrichten lokal speichern (History) und ausgeben</li>
 *   <li>Bei User-Nachrichten: Antwort erzeugen und als neue Chat-Nachricht senden</li>
 * </ol>
 *
 * <p>Die AI antwortet nicht auf eigene Nachrichten (Schutz vor Endlos-Schleifen).
 */
public final class AiController {
    private static final Logger log = LogManager.getLogger(AiController.class);
    private final ChatHistory model;
    private final CliView view;
    private final SocketClient socket;
    private final AIResponder responder;
    private final AiPersonaFactory personaFactory;
    private final String personaId;

    private volatile Persona persona;
    private final AtomicBoolean softShutdownStarted = new AtomicBoolean(false);

    /**
     * @param model lokales Chat-Log (nur fuer den Client, der Server speichert nicht)
     * @param view CLI-Ausgabe; die AI liest nicht interaktiv von stdin
     * @param socket TCP-Client zum Server
     * @param responder AI-Anbindung (Antwortgenerierung)
     * @param personaFactory Persona-Erzeugung (vordefiniert oder dynamisch)
     * @param personaId Persona-ID aus der CLI
     */
    public AiController(ChatHistory model,
                        CliView view,
                        SocketClient socket,
                        AIResponder responder,
                        AiPersonaFactory personaFactory,
                        String personaId) {
        this.model = model;
        this.view = view;
        this.socket = socket;
        this.responder = responder;
        this.personaFactory = personaFactory;
        this.personaId = personaId;
    }

    /**
     * Startet den Bot und blockiert bis zur Beendigung (Socket geschlossen oder Interrupt).
     */
    public void runBlocking() {
        persona = personaFactory.createPersona(personaId);
        view.system("AI persona: " + persona.displayName() + " (" + persona.id() + ")");
        log.info("AI persona: {} ({})", persona.displayName(), persona.id());

        socket.setOnLine(line -> {
            log.info("AI received line: '{}'", line);
            ChatMessage msg = ChatController.Protocol.decode(line);
            if (msg == null) {
                log.warn("AI failed to decode line: '{}'", line);
                return;
            }

            model.add(msg);
            view.print(msg);

            if (msg.type() != ChatMessage.Type.USER) {
                log.debug("AI ignoring non-user message");
                return;
            }
            if (persona.displayName().equalsIgnoreCase(msg.author())) {
                log.debug("AI ignoring own message");
                return;
            }

            log.info("AI responding to message from '{}'", msg.author());
            tryRespond(msg);
        });

        try {
            socket.connect();
        } catch (Exception e) {
            view.system("Connect failed: " + e.getMessage());
            log.error("Connect failed", e);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            softShutdown("jvm-shutdown");
        }, "shutdown-hook-ai"));

        socket.sendLine(ChatController.Protocol.encodeNick(persona.displayName()));
        view.system("Connected as AI. Press Ctrl+C to exit.");
        log.info("AI connected and registered nickname");

        while (!socket.isClosed()) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Best-effort "Soft Shutdown":
     * <ul>
     *   <li>sendet eine persoenliche Abschieds-Nachricht als normale Chat-Nachricht</li>
     *   <li>sendet anschliessend {@code QUIT|...}, damit der Server eine Systemmeldung broadcastet</li>
     * </ul>
     *
     * <p>Wird typischerweise aus einem Shutdown-Hook aufgerufen (Ctrl+C / Fenster schliessen).
     *
     * @param reason technische Ursache fuer das Logging des Shutdowns
     */
    private void softShutdown(String reason) {
        if (!softShutdownStarted.compareAndSet(false, true)) return;

        Persona p = persona;
        String nick = (p != null && p.displayName() != null && !p.displayName().isBlank())
                ? p.displayName()
                : "anon";

        try {
            if (!socket.isClosed()) {
                String bye = buildFarewellMessage(p);
                socket.sendLine(ChatController.Protocol.encodeUserMessage(nick, bye));
                socket.sendLine(ChatController.Protocol.encodeSystemClientQuit(nick));

                // Give the OS + TCP stack a short moment to flush before we close the socket
                // (important for console-close events on Windows).
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.info("AI soft shutdown sent (reason={})", reason);
            }
        } catch (Exception e) {
            log.debug("AI soft shutdown failed (reason={})", reason, e);
        } finally {
            socket.close();
        }
    }

    /**
     * Leitet aus der aktiven Persona eine kurze Abschieds-Nachricht ab.
     *
     * <p>Bekannte Personas koennen einen stilistisch passenden Satz erhalten; fuer unbekannte
     * oder fehlende Personas wird ein neutraler Standardtext verwendet.
     *
     * @param persona aktuell verwendete Persona, optional {@code null}
     * @return textuelle Abschieds-Nachricht fuer den letzten User-Post des Bots
     */
    private static String buildFarewellMessage(Persona persona) {
        if (persona == null) return "Ich verabschiede mich, bis spaeter.";
        String id = persona.id() == null ? "" : persona.id().trim().toLowerCase();
        return switch (id) {
            case "albert_zweistein" -> "Ich verabschiede mich. Bis zum naechsten kleinen Gedankenexperiment.";
            case "tom_sawyer" -> "Muss los, es gibt noch Arbeit am Zaun. Macht's gut.";
            default -> "Ich verabschiede mich, bis spaeter.";
        };
    }

    /**
     * Erzeugt eine Antwort basierend auf Persona und lokaler History und sendet sie an den Server.
     */
    private void tryRespond(ChatMessage lastUserMessage) {
        List<ChatMessage> history = model.snapshot();
        String reply = responder.respond(persona, history, lastUserMessage);
        if (reply == null || reply.isBlank()) return;
        log.debug("AI produced reply (chars={})", reply.length());
        socket.sendLine(ChatController.Protocol.encodeUserMessage(persona.displayName(), reply.trim()));
    }
}
