package de.larlibu.aichat.controller;

import de.larlibu.aichat.model.ChatHistory;
import de.larlibu.aichat.model.ChatMessage;
import de.larlibu.aichat.network.SocketClient;
import de.larlibu.aichat.view.CliView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

/**
 * Interaktiver Konsolen-Client (Mensch).
 *
 * <p>Aufgaben:
 * <ul>
 *   <li>Nickname abfragen</li>
 *   <li>Zum Server verbinden</li>
 *   <li>Konsoleingaben als Nachrichten senden</li>
 *   <li>Eingehende Zeilen dekodieren und anzeigen</li>
 * </ul>
 *
 * <p>Die Kommunikation nutzt ein einfaches Zeilen-Protokoll (siehe {@link Protocol}).
 */
public final class ChatController {
    private static final Logger log = LogManager.getLogger(ChatController.class);
    private final ChatHistory model;
    private final CliView view;
    private final SocketClient socket;

    /**
     * @param m lokales Chat-Log fuer diesen Client
     * @param v CLI-Ein/Ausgabe
     * @param s TCP-Client zum Server
     */
    public ChatController(ChatHistory m, CliView v, SocketClient s) {
        this.model = m;
        this.view = v;
        this.socket = s;
    }

    /**
     * Startet die Client-Loop und blockiert, bis der User beendet.
     */
    public void runBlocking() {
        String nick = promptNick();
        if (nick == null || nick.isBlank()) {
            view.system("No nickname provided. Exiting.");
            log.warn("Human client exited: no nickname provided");
            return;
        }
        log.info("Human nickname='{}'", nick);

        socket.setOnLine(line -> {
            ChatMessage msg = Protocol.decode(line);
            if (msg != null) {
                model.add(msg);
                view.print(msg);
            }
        });

        try {
            socket.connect();
        } catch (Exception e) {
            view.system("Connect failed: " + e.getMessage());
            log.error("Connect failed", e);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socket.sendLine(Protocol.encodeSystemClientQuit(nick));
            } catch (Exception ignored) {
            } finally {
                socket.close();
            }
        }, "shutdown-hook-human"));

        socket.sendLine(Protocol.encodeNick(nick));
        view.system("Connected. Type messages. Use /quit to exit.");
        log.info("Human connected and registered nickname");

        while (true) {
            String line = view.readLine("> ");
            if (line == null) {
                log.info("stdin closed; exiting human client");
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("/quit".equalsIgnoreCase(line)) {
                socket.sendLine(Protocol.encodeSystemClientQuit(nick));
                view.system("Bye.");
                log.info("Human requested quit");
                socket.close();
                break;
            }
            String wire = Protocol.encodeUserMessage(nick, line);
            log.info("Human sending: '{}'", wire);
            socket.sendLine(wire);
        }
    }

    /**
     * Fragt den Nickname ueber die View ab.
     *
     * @return getrimmter Nickname oder {@code null} bei I/O Fehler/EOF
     */
    private String promptNick() {
        String nick = view.readLine("Nickname: ");
        return nick == null ? null : nick.trim();
    }

    // Shared protocol helpers for client/controllers (kept here to avoid extra files).
    /**
     * Kodierung/Dekodierung fuer das Wire-Format zwischen Client und Server.
     *
     * <p>Wire-Format (eine Zeile pro Event):
     * <ul>
     *   <li>{@code NICK|<nick>} - setzt/veraendert den Nickname auf Serverseite</li>
     *   <li>{@code QUIT|<nick>} - informiert ueber geordnetes Verlassen</li>
     *   <li>{@code MSG|<epochMilli>|<nick>|<text>} - User-Nachricht</li>
     *   <li>{@code SYS|<epochMilli>|<text>} - System-Nachricht (vom Server)</li>
     * </ul>
     *
     * <p>Escape-Regeln: Backslash, Pipe und Zeilenumbrueche werden escaped, damit alles in einer Zeile bleibt.
     */
    public static final class Protocol {
        private Protocol() {
        }

        // Wire format:
        //   SYS|<epochMilli>|<text>
        //   MSG|<epochMilli>|<nick>|<text>
        /**
         * Kodiert einen Nickname-Update.
         *
         * @param nick neuer Nickname
         * @return Protokollzeile {@code NICK|<nick>}
         */
        public static String encodeNick(String nick) {
            return "NICK|" + escape(nick);
        }

        /**
         * Kodiert ein "Client verlaesst den Chat"-Event.
         *
         * @param nick Nickname des verlassenden Clients
         * @return Protokollzeile {@code QUIT|<nick>}
         */
        public static String encodeSystemClientQuit(String nick) {
            return "QUIT|" + escape(nick);
        }

        /**
         * Kodiert eine User-Nachricht mit aktuellem Timestamp.
         *
         * @param nick Absender-Nickname
         * @param text Nachrichtentext
         * @return Protokollzeile {@code MSG|<epochMilli>|<nick>|<text>}
         */
        public static String encodeUserMessage(String nick, String text) {
            long ts = Instant.now().toEpochMilli();
            return "MSG|" + ts + "|" + escape(nick) + "|" + escape(text);
        }

        /**
         * Dekodiert eine vom Server empfangene Zeile zu {@link ChatMessage}.
         *
         * @param line eine Protokollzeile (z.B. {@code SYS|...} oder {@code MSG|...})
         * @return Message oder {@code null}, wenn ungueltig/unerkannt
         */
        public static ChatMessage decode(String line) {
            try {
                String[] parts = line.split("\\|", 4);
                if (parts.length < 2) return null;
                String kind = parts[0];
                if ("SYS".equals(kind)) {
                    String[] p3 = line.split("\\|", 3);
                    long ts = Long.parseLong(p3[1]);
                    String text = p3.length >= 3 ? unescape(p3[2]) : "";
                    return ChatMessage.system(Instant.ofEpochMilli(ts), text);
                }
                if ("MSG".equals(kind)) {
                    if (parts.length != 4) return null;
                    long ts = Long.parseLong(parts[1]);
                    String nick = unescape(parts[2]);
                    String text = unescape(parts[3]);
                    return ChatMessage.user(Instant.ofEpochMilli(ts), nick, text);
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Escaped Protokoll-Sonderzeichen, damit alles in einer Zeile bleiben kann.
         */
        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "\\r");
        }

        /**
         * Gegenstueck zu {@link #escape(String)}.
         */
        private static String unescape(String s) {
            StringBuilder out = new StringBuilder(s.length());
            boolean esc = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!esc) {
                    if (c == '\\') {
                        esc = true;
                    } else {
                        out.append(c);
                    }
                    continue;
                }
                esc = false;
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 'p' -> out.append('|');
                    case '\\' -> out.append('\\');
                    default -> out.append(c);
                }
            }
            if (esc) out.append('\\');
            return out.toString();
        }
    }
}
