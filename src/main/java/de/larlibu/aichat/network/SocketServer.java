package de.larlibu.aichat.network;

import de.larlibu.aichat.service.ChatRoomManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP-Chatserver, der alle empfangenen Nachrichten an alle verbundenen Clients broadcastet.
 *
 * <p>Protokoll:
 * <ul>
 *   <li>{@code NICK|...} aendert den Nickname fuer Join/Leave-Meldungen</li>
 *   <li>{@code QUIT|...} sendet eine Systemmeldung und trennt die Verbindung</li>
 *   <li>{@code MSG|...} wird unveraendert an alle Clients weitergeleitet</li>
 * </ul>
 *
 * <p>Threading: fuer jede Client-Verbindung wird ein Task in einem Virtual Thread ausgefuehrt.
 * Der Server selbst speichert keine Historie.
 */
public final class SocketServer {
    private static final Logger log = LogManager.getLogger(SocketServer.class);
    private final int port;
    private final ChatRoomManager room;
    private final ExecutorService clientPool = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ServerSocket serverSocket;

    /**
     * @param port TCP-Port, auf dem der Server lauscht
     * @param room zentraler Raum fuer Broadcasts und Client-Registrierung
     */
    public SocketServer(int port, ChatRoomManager room) {
        this.port = port;
        this.room = Objects.requireNonNull(room);
    }

    /**
     * Startet den Server und blockiert, bis der Server gestoppt wird oder ein Fehler auftritt.
     */
    public void startBlocking() {
        running.set(true);
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            log.info("Server listening on TCP:{}", port);
            while (running.get()) {
                Socket s = ss.accept();
                log.debug("Accepted connection from {}", s.getRemoteSocketAddress());
                clientPool.submit(() -> handleClient(s));
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("Server error", e);
            }
        } finally {
            stopSoft("Server stopped.");
        }
    }

    /**
     * Stoppt den Server "sanft" (best effort): broadcastet ein Goodbye, schliesst alle Clients, schliesst den Listener.
     *
     * @param goodbye Text fuer eine Systemmeldung
     */
    public void stopSoft(String goodbye) {
        if (!running.getAndSet(false)) return;
        log.info("Stopping server: {}", goodbye);
        try {
            room.broadcastSystem(goodbye);
        } catch (Exception ignored) {
        }
        try {
            room.closeAll();
        } catch (Exception ignored) {
        }
        try {
            ServerSocket ss = serverSocket;
            if (ss != null) ss.close();
        } catch (Exception ignored) {
        }
        try {
            clientPool.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    /**
     * Bearbeitet eine einzelne Client-Verbindung (Protokoll-Parsing + Broadcast).
     *
     * <p>Falls ein Client ohne explizites {@code QUIT|...} verschwindet, wird im {@code finally}
     * dennoch eine sichtbare Disconnect-Systemmeldung erzeugt, damit der Chatzustand fuer die
     * anderen Teilnehmer nachvollziehbar bleibt.
     *
     * @param socket akzeptierte TCP-Verbindung
     */
    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            ChatRoomManager.ClientConnection conn = room.register(out);
            String nick = "anon";
            room.broadcastSystem(nick + " joined.");
            boolean quit = false;

            try {
                String line;
                while ((line = in.readLine()) != null) {
                    log.trace("Received from client {}: '{}'", s.getRemoteSocketAddress(), line);
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("NICK|")) {
                        String n = unescape(line.substring("NICK|".length()));
                        if (!n.isBlank()) {
                            String old = nick;
                            nick = n;
                            room.broadcastSystem(old + " is now known as " + nick + ".");
                            log.info("Client {} set nickname '{}' -> '{}'", s.getRemoteSocketAddress(), old, nick);
                        }
                        continue;
                    }

                    if (line.startsWith("QUIT|")) {
                        quit = true;
                        String who = unescape(line.substring("QUIT|".length()));
                        room.broadcastSystem((who.isBlank() ? nick : who) + " left. Bye.");
                        log.info("Client {} quit (nick='{}')", s.getRemoteSocketAddress(), nick);
                        break;
                    }

                    if (line.startsWith("MSG|")) {
                        log.info("Broadcasting message from {}: {}", nick, line);
                        room.broadcastRaw(line);
                        continue;
                    }
                    
                    log.warn("Unknown protocol line from {}: '{}'", s.getRemoteSocketAddress(), line);
                }
            } finally {
                // If the client vanishes without QUIT (window closed, process killed, network error),
                // we still want a visible hint in the chat.
                if (!quit) {
                    try {
                        room.broadcastSystem(nick + " disconnected.");
                    } catch (Exception ignored) {
                    }
                    log.info("Client {} disconnected without QUIT (nick='{}')", s.getRemoteSocketAddress(), nick);
                }
                room.unregister(conn);
            }
            log.debug("Client disconnected {}", s.getRemoteSocketAddress());
        } catch (IOException e) {
            // ignore noisy disconnects
            log.debug("Client IO ended");
        } catch (Exception e) {
            log.error("Client handler error", e);
        }
    }

    /**
     * Unescape fuer NICK/QUIT Payloads (muss zum Client-Escape passen).
     *
     * @param s escapeter Payload aus dem Wire-Format
     * @return decodierter Text fuer Nicknames oder Quit-Nachrichten
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

    /**
     * Kodiert eine Systemmeldung ins Wire-Format.
     *
     * @param text Systemtext
     * @return Protokollzeile {@code SYS|<epochMilli>|<text>}
     */
    public static String encodeSystem(String text) {
        return "SYS|" + Instant.now().toEpochMilli() + "|" + escape(text);
    }

    /**
     * Escape fuer System-Text (muss zum Client-Unescape passen).
     *
     * @param s roher Systemtext
     * @return Protokoll-sicherer Text ohne ungeescapte Trenner
     */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "\\r");
    }
}
