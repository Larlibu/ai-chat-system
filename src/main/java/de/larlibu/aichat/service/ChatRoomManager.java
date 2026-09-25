package de.larlibu.aichat.service;

import de.larlibu.aichat.network.SocketServer;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet alle verbundenen Clients und broadcastet Nachrichten an alle.
 *
 * <p>Thread-Safety: nutzt ein concurrent Set; Broadcast iteriert ueber eine Momentaufnahme des Sets.
 * Das ist fuer diese einfache Broadcast-Logik ausreichend.
 */
public final class ChatRoomManager {
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();

    /**
     * Registriert einen Client-Ausgabestream im Raum.
     *
     * @param out Writer zum Client-Socket
     * @return Connection-Handle fuer spaeteres {@link #unregister(ClientConnection)}
     */
    public ClientConnection register(PrintWriter out) {
        ClientConnection c = new ClientConnection(out);
        clients.add(c);
        return c;
    }

    /**
     * Entfernt einen Client und schliesst seinen Writer (best effort).
     *
     * @param c Connection-Handle aus {@link #register(PrintWriter)}; {@code null} wird ignoriert
     */
    public void unregister(ClientConnection c) {
        if (c == null) return;
        clients.remove(c);
        try {
            c.out.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Broadcastet eine rohe Protokollzeile an alle Clients.
     *
     * @param line Protokollzeile (z.B. {@code MSG|...} oder {@code SYS|...})
     */
    public void broadcastRaw(String line) {
        for (ClientConnection c : clients) {
            c.out.println(line);
            c.out.flush();
        }
    }

    /**
     * Broadcastet eine Systemmeldung (wird ins Wire-Format kodiert).
     *
     * @param text Systemtext (z.B. Join/Leave-Meldung)
     */
    public void broadcastSystem(String text) {
        broadcastRaw(SocketServer.encodeSystem(text));
    }

    /**
     * Schliesst alle Clients und leert die Liste.
     */
    public void closeAll() {
        for (ClientConnection c : clients) {
            try {
                c.out.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }

    /**
     * Interner Handle fuer eine Verbindung.
     */
    public static final class ClientConnection {
        private final PrintWriter out;

        private ClientConnection(PrintWriter out) {
            this.out = Objects.requireNonNull(out);
        }
    }
}
