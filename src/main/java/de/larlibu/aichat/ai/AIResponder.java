package de.larlibu.aichat.ai;

import de.larlibu.aichat.model.ChatMessage;
import de.larlibu.aichat.model.Persona;

import java.util.List;

/**
 * Abstraktion fuer einen "Antwort-Generator".
 *
 * <p>Implementierungen koennen lokal (Regeln) oder remote (API) antworten.
 * Die Rueckgabe ist reiner Text, der spaeter als Chat-Nachricht gesendet wird.
 */
public interface AIResponder {
    /**
     * Erzeugt eine Antwort im Stil der gegebenen Persona.
     *
     * @param persona die Persona (Name + System-Prompt)
     * @param history letzte Nachrichten (kann leer sein)
     * @param lastUserMessage die letzte empfangene User-Nachricht (kann {@code null} sein)
     * @return Antworttext oder {@code null}/{@code blank}, wenn nicht geantwortet werden soll
     */
    String respond(Persona persona, List<ChatMessage> history, ChatMessage lastUserMessage);
}
