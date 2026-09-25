package de.larlibu.aichat.model;

/**
 * Persona-Konfiguration fuer den AI-Client.
 *
 * <p>Der {@code displayName} wird als Nickname im Chat verwendet. {@code systemPrompt} wird an den
 * {@link de.larlibu.aichat.ai.AIResponder} gegeben, um Stil und Rolle vorzugeben.
 *
 * @param id interne ID (z.B. {@code albert_zweistein})
 * @param displayName sichtbarer Name im Chat (Nickname)
 * @param systemPrompt System-Anweisung (Prompt) fuer die AI
 */
public record Persona(String id, String displayName, String systemPrompt) {
}
