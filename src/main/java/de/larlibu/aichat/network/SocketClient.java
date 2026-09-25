package de.larlibu.aichat.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Kleiner TCP-Client fuer zeilenbasierte Kommunikation.
 *
 * <p>Beim {@link #connect()} wird ein Reader in einem Virtual Thread gestartet, der jede empfangene Zeile an
 * {@link #setOnLine(Consumer)} weiterreicht.
 *
 * <p>Diese Klasse implementiert bewusst kein Reconnect/Backoff und keine Protokoll-Validierung.
 */
public final class SocketClient {
    private static final Logger log = LogManager.getLogger(SocketClient.class);
    private final String host;
    private final int port;

    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile BufferedReader in;
    private volatile Thread readerThread;
    private volatile Consumer<String> onLine = s -> {};

    private volatile boolean closed;

    /**
     * @param host Ziel-Host des Chat-Servers
     * @param port Ziel-Port des Chat-Servers
     */
    public SocketClient(String host, int port) {
        this.host = Objects.requireNonNull(host);
        this.port = port;
    }

    /**
     * Setzt einen Callback, der fuer jede empfangene Zeile aufgerufen wird.
     *
     * @param onLine Callback; {@code null} deaktiviert (no-op)
     */
    public void setOnLine(Consumer<String> onLine) {
        this.onLine = (onLine == null) ? (s -> {}) : onLine;
    }

    /**
     * Baut die Socket-Verbindung auf und startet den Reader-Loop.
     *
     * @throws IOException wenn Verbindungsaufbau fehlschlaegt
     */
    public void connect() throws IOException {
        log.info("Connecting to {}:{}", host, port);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5_000);
        this.socket = s;
        this.out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        this.in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        this.closed = false;

        readerThread = Thread.ofVirtual().name("socket-client-reader").start(this::readLoop);
        log.info("Connected to {}:{}", host, port);
    }

    /**
     * Sendet eine Zeile (mit {@code println}) an den Server.
     *
     * @param line zu sendende Protokollzeile
     */
    public void sendLine(String line) {
        PrintWriter o = out;
        if (o == null) return;
        o.println(line);
        o.flush();
    }

    /**
     * @return {@code true}, wenn der Client geschlossen ist (lokal oder durch EOF)
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Schliesst den Client (best effort) und beendet den Reader.
     */
    public void close() {
        closed = true;
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Interner Reader-Loop (Virtual Thread).
     */
    private void readLoop() {
        try {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                onLine.accept(line);
            }
        } catch (Exception ignored) {
        } finally {
            closed = true;
            try {
                Socket s = socket;
                if (s != null) s.close();
            } catch (Exception ignored) {
            }
            log.info("Disconnected from {}:{}", host, port);
        }
    }
}
