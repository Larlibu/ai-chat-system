package de.larlibu.aichat.ai;

import de.larlibu.aichat.model.ChatMessage;
import de.larlibu.aichat.model.Persona;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erzeugt {@link Persona}-Objekte aus einer Persona-ID.
 *
 * <p>Einige Personas sind fest verdrahtet. Der Spezialwert {@code "?"} laesst die AI
 * eine neue Persona generieren (Name + System-Prompt), die dann im Chat verwendet wird.
 */
public final class AiPersonaFactory {
    private static final Pattern NAME_PROMPT =
            Pattern.compile("^\\s*NAME\\s*:\\s*(.+?)\\s*\\R\\s*PROMPT\\s*:\\s*(.+)\\s*$", Pattern.DOTALL);

    private static final List<PersonaOption> OPTIONS = List.of(
            new PersonaOption(
                    "tom_sawyer",
                    "Tom Sawyer",
                    "Rustikaler Erzahler mit trockenem Humor und Hang zu handfesten Bildern."
            ),
            new PersonaOption(
                    "albert_zweistein",
                    "Albert Zweistein",
                    "Neugieriger Erklaerer mit Denkexperimenten und klarer Sprache."
            ),
            new PersonaOption(
                    "?",
                    "Mystery Guest",
                    "Generiert ad hoc eine neue, zufaellige Persona mit eigener Hintergrundgeschichte."
            )
    );

    private final AIResponder responder;

    /**
     * Schlanke Beschreibung einer waehlbaren Persona fuer die CLI-Auswahl.
     *
     * @param id technische Persona-ID fuer CLI/Factory
     * @param displayName sichtbarer Name der Option
     * @param description kurze Beschreibung fuer den Nutzer
     */
    public record PersonaOption(String id, String displayName, String description) {
    }

    /**
     * @param responder Responder, der fuer die dynamische Persona-Generierung ({@code "?"}) genutzt wird
     */
    public AiPersonaFactory(AIResponder responder) {
        this.responder = Objects.requireNonNull(responder);
    }

    /**
     * Liefert die beim Start waehlbaren Persona-Optionen.
     *
     * @return unveraenderliche Liste bekannter Persona-Optionen
     */
    public List<PersonaOption> availableOptions() {
        return OPTIONS;
    }

    /**
     * Erstellt eine Persona aus einer ID.
     *
     * @param id z.B. {@code tom_sawyer}, {@code albert_zweistein} oder {@code ?}
     * @return Persona (nie {@code null})
     */
    public Persona createPersona(String id) {
        String raw = id == null ? "" : id.trim();
        String key = raw.toLowerCase();
        return switch (key) {
            case "tom_sawyer" -> new Persona(
                    "tom_sawyer",
                    "Tom Sawyer",
                    "Du bist Tom Sawyer, ein wortgewandter Abenteurer mit laendlichem Hintergrund, improvisierst gern, "
                            + "erzaehlst anschaulich und antwortest mit bodenstaendigem Witz. Du wirkst wie jemand, "
                            + "der den Mississippi, Zaeune, Handel, Schummeleien und Alltagsklugheit aus erster Hand "
                            + "kennt. In Gruppenchats bleibst du charmant, direkt und leicht schelmisch, ohne boesartig "
                            + "zu werden."
            );
            case "albert_zweistein" -> new Persona(
                    "albert_zweistein",
                    "Albert Zweistein",
                    "Du bist Albert Zweistein, ein neugieriger, freundlicher Denker mit trockenem Humor. Du erklaerst "
                            + "komplexe Dinge klar, strukturiert und gern mit kleinen Gedankenexperimenten oder "
                            + "Alltagsanalogien. Du bleibst in Diskussionen gelassen, praezise und respektvoll, "
                            + "formulierst auf Deutsch und sagst offen, wenn etwas unklar oder unsicher ist."
            );
            case "?" -> createMysteryPersona();
            default -> new Persona(
                    key.isBlank() ? "guest" : key,
                    raw.isBlank() ? "Guest" : raw,
                    "Du bist eine hilfreiche Chat-Persona mit eigener Stimme. Antworte auf Deutsch, bleib in deiner "
                            + "Rolle und verhalte dich in einem gemeinsamen Gruppenchat respektvoll und konsistent."
            );
        };
    }

    /**
     * Laesst die AI eine neue Persona generieren.
     *
     * <p>Erwartetes Ausgabeformat (exakt):
     * <pre>
     * NAME: &lt;display name&gt;
     * PROMPT: &lt;one-paragraph system prompt in German&gt;
     * </pre>
     *
     * <p>Wenn das Parsing fehlschlaegt, wird eine einfache Fallback-Persona genutzt.
     */
    private Persona createMysteryPersona() {
        String seed = """
                Create a brand new, surprising chat persona for a shared multi-user chat room.
                Invent:
                - a unique display name
                - a detailed German system prompt with a clear background story, worldview, tone, quirks, motives and group-chat behavior

                Requirements:
                - do not reuse existing famous fictional characters or celebrities
                - make the persona creative but internally coherent
                - the prompt must be detailed, but fit into one compact paragraph
                - the persona should feel distinct enough to chat with repeatedly

                Output exactly:
                NAME: <display name>
                PROMPT: <detailed German system prompt>
                No extra lines.
                """;

        Persona generator = new Persona(
                "mystery_seed",
                "Mystery Guest Generator",
                "You design original chat personas and follow the requested output format exactly."
        );
        String raw = responder.respond(generator, List.of(), ChatMessage.user(Instant.now(), "orchestrator", seed));
        if (raw == null) raw = "";

        Matcher m = NAME_PROMPT.matcher(raw);
        if (!m.find()) {
            return new Persona(
                    "mystery_" + UUID.randomUUID(),
                    "Mystery Guest",
                    "Du bist eine geheimnisvolle, aber freundliche Figur mit unbekannter Herkunft, einer klaren eigenen "
                            + "Stimme und einer Vorliebe fuer ungewoehnliche Beobachtungen. Du antwortest auf Deutsch, "
                            + "bleibst im Gruppenchat konsistent in deiner Rolle und laesst immer wieder kleine Hinweise "
                            + "auf deine seltsame Hintergrundgeschichte einfliessen."
            );
        }

        String name = m.group(1).trim();
        String prompt = m.group(2).trim();
        return new Persona(
                "mystery_" + UUID.randomUUID(),
                name.isBlank() ? "Mystery Guest" : name,
                prompt.isBlank()
                        ? "Du bist ein spontan erschaffener Mystery Guest mit eigener Geschichte, eigener Stimme und "
                        + "klarer Perspektive. Antworte auf Deutsch und bleib in deiner Rolle."
                        : prompt
        );
    }
}
