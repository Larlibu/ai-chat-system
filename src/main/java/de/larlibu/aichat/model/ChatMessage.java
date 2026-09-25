package de.larlibu.aichat.model;

import java.time.Instant;

/**
 * Eine Chat-Nachricht im Client.
 *
 * <p>Hinweis: Der Server sendet/empfaengt Nachrichten als Protokollzeilen; die Client-Seite dekodiert diese
 * Zeilen zu {@code ChatMessage}.
 *
 * @param timestamp Zeitpunkt der Nachricht (Instant)
 * @param author Author/Nickname; bei {@link Type#SYSTEM} typischerweise {@code null}
 * @param text Nachrichtentext
 * @param type Nachrichtentyp (User vs System)
 */
public record ChatMessage(Instant timestamp, String author, String text, Type type) {
    /**
     * Unterscheidet normale Chat-Nachrichten von Systemmeldungen (Join/Leave/Rename etc.).
     */
    public enum Type { USER, SYSTEM }

    /**
     * Convenience-Factory fuer User-Nachrichten.
     *
     * @param ts Zeitpunkt der Nachricht
     * @param author Absender-Nickname
     * @param text Nachrichtentext
     * @return neue User-{@code ChatMessage}
     */
    public static ChatMessage user(Instant ts, String author, String text) {
        return new ChatMessage(ts, author, text, Type.USER);
    }

    /**
     * Convenience-Factory fuer System-Nachrichten.
     *
     * @param ts Zeitpunkt der Nachricht
     * @param text Systemtext
     * @return neue System-{@code ChatMessage} (ohne Autor)
     */
    public static ChatMessage system(Instant ts, String text) {
        return new ChatMessage(ts, null, text, Type.SYSTEM);
    }
}
