package de.larlibu.aichat.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Speicher fuer eine (client-lokale) Chat-Historie.
 *
 * <p>Der Server speichert in diesem Projekt keine Historie; jeder Client haelt seine eigene Sicht.
 * Die AI nutzt die Historie als Kontext fuer Antworten.
 */
public interface ChatHistory {
    /**
     * Fuegt eine Nachricht hinzu.
     *
     * @param message Nachricht, die der Historie angehaengt wird
     */
    void add(ChatMessage message);

    /**
     * Liefert eine Momentaufnahme der aktuellen Historie.
     *
     * @return unveraenderliche Liste (Snapshot)
     */
    List<ChatMessage> snapshot();

    /**
     * Convenience-Methode fuer System-Nachrichten (Zeitpunkt = jetzt).
     *
     * @param text Systemtext
     */
    default void addSystem(String text) {
        add(ChatMessage.system(Instant.now(), text));
    }

    /**
     * Einfache In-Memory-Implementierung.
     *
     * <p>Thread-Safety: Methoden sind {@code synchronized}, da eingehende Socket-Nachrichten in einem Reader-Thread
     * ankommen koennen.
     */
    final class InMemoryChatHistory implements ChatHistory {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public synchronized void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public synchronized List<ChatMessage> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }
    }
}
