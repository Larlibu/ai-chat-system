package de.larlibu.aichat.ai;

import de.larlibu.aichat.model.ChatMessage;
import de.larlibu.aichat.model.Persona;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link AIResponder}-Implementierung, die Google Gemini via HTTP aufruft.
 *
 * <p>Konfiguration:
 * <ul>
 *   <li>{@code gemini.api.key}: API-Key (wird ueblicherweise aus {@code GEMINI_KEY} gelesen)</li>
 *   <li>{@code gemini.base.url}: Default {@code https://generativelanguage.googleapis.com}</li>
 *   <li>{@code gemini.model}: Default {@code gemini-1.5-flash}</li>
 *   <li>{@code gemini.retry.max.attempts}: Anzahl Versuche fuer temporaere Fehler (Default {@code 3})</li>
 *   <li>{@code gemini.retry.initial.delay.ms}: initialer Retry-Backoff in ms (Default {@code 500})</li>
 *   <li>{@code gemini.retry.max.delay.ms}: maximaler Retry-Backoff in ms (Default {@code 5000})</li>
 * </ul>
 *
 * <p>Hinweis: Das JSON-Parsing ist absichtlich simpel (Regex auf {@code "text": "..."}). Das ist nicht robust,
 * reicht aber fuer ein Lern-/Demo-Projekt.
 */
public final class RealGeminiResponder implements AIResponder {
    private static final Logger log = LogManager.getLogger(RealGeminiResponder.class);
    private final HttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);

    /**
     * Baut einen Responder aus geladenen Properties.
     *
     * @param props Properties (z.B. aus {@code application.properties}), ggf. schon mit Env-Variablen aufgeloest
     */
    public RealGeminiResponder(Properties props) {
        Objects.requireNonNull(props);
        this.apiKey = prop(props, "gemini.api.key");
        this.baseUrl = prop(props, "gemini.base.url", "https://generativelanguage.googleapis.com");
        this.model = prop(props, "gemini.model", "gemini-1.5-flash");
        this.maxAttempts = readIntProp(props, "gemini.retry.max.attempts", 3, 1, 10);
        this.initialBackoffMs = readLongProp(props, "gemini.retry.initial.delay.ms", 500L, 50L, 120_000L);
        this.maxBackoffMs = readLongProp(props, "gemini.retry.max.delay.ms", 5_000L, this.initialBackoffMs, 300_000L);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String respond(Persona persona, List<ChatMessage> history, ChatMessage lastUserMessage) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.warn("Gemini API key missing (set GEMINI_KEY env var or -Dgemini.api.key=...)");
            return "[Gemini API key missing: set GEMINI_KEY env var]";
        }

        String prompt = buildPrompt(persona, history, lastUserMessage);
        String payload = buildJsonPayload(persona.systemPrompt(), prompt);

        String endpointBase = normalizeEndpointBase(baseUrl);
        String url = endpointBase + "/models/" + model + ":generateContent?key=" + apiKey;
        String urlNoKey = endpointBase + "/models/" + model + ":generateContent";
        log.debug("Gemini request POST {}", urlNoKey);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = res.statusCode();
                if (status / 100 == 2) {
                    String text = extractFirstText(res.body());
                    if (text == null) {
                        log.warn("Gemini parse error (response starts with: {})", abbreviate(res.body(), 300));
                        return "[Gemini parse error]";
                    }
                    return text.trim();
                }

                if (isRetryableStatus(status) && attempt < maxAttempts) {
                    long delayMs = computeRetryDelayMs(attempt, res.headers().firstValue("Retry-After").orElse(null));
                    log.warn(
                            "Gemini HTTP {} (body starts with: {}), retry in {} ms (attempt {}/{})",
                            status, abbreviate(res.body(), 300), delayMs, attempt, maxAttempts
                    );
                    if (!sleepForRetry(delayMs)) {
                        return "[Gemini error: InterruptedException]";
                    }
                    continue;
                }

                log.warn("Gemini HTTP {} (body starts with: {})", status, abbreviate(res.body(), 300));
                return "[Gemini HTTP " + status + "]";
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    long delayMs = computeRetryDelayMs(attempt, null);
                    log.warn("Gemini request failed: {}, retry in {} ms (attempt {}/{})",
                            e.toString(), delayMs, attempt, maxAttempts);
                    if (!sleepForRetry(delayMs)) {
                        return "[Gemini error: InterruptedException]";
                    }
                    continue;
                }
                log.warn("Gemini request failed: {}", e.toString());
                return "[Gemini error: " + e.getClass().getSimpleName() + "]";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Gemini request interrupted: {}", e.toString());
                return "[Gemini error: InterruptedException]";
            } catch (Exception e) {
                log.warn("Gemini error", e);
                return "[Gemini error]";
            }
        }

        return "[Gemini error]";
    }

    /**
     * Normalisiert die konfigurierte Base-URL.
     *
     * <p>Akzeptiert sowohl:
     * <ul>
     *   <li>{@code https://generativelanguage.googleapis.com}</li>
     *   <li>{@code https://generativelanguage.googleapis.com/v1beta}</li>
     * </ul>
     *
     * <p>Wenn keine API-Version am Ende steht, wird {@code /v1beta} angehaengt.
     */
    private static String normalizeEndpointBase(String rawBaseUrl) {
        String root = (rawBaseUrl == null) ? "" : rawBaseUrl.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (root.endsWith("/v1beta") || root.endsWith("/v1")) return root;
        if (root.isEmpty()) return "https://generativelanguage.googleapis.com/v1beta";
        return root + "/v1beta";
    }

    private static String abbreviate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, Math.max(0, maxChars)) + "...";
    }

    private static String buildPrompt(Persona persona, List<ChatMessage> history, ChatMessage lastUserMessage) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("You are chatting in a single shared room.\n");
        sb.append("Stay in persona.\n\n");
        sb.append("Recent messages:\n");

        int max = Math.min(history.size(), 20);
        for (int i = history.size() - max; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m.type() == ChatMessage.Type.SYSTEM) {
                sb.append("* ").append(m.text()).append("\n");
            } else {
                sb.append(m.author()).append(": ").append(m.text()).append("\n");
            }
        }

        if (lastUserMessage != null) {
            sb.append("\nLast message:\n");
            if (lastUserMessage.type() == ChatMessage.Type.SYSTEM) {
                sb.append("* ").append(lastUserMessage.text()).append("\n");
            } else {
                sb.append(lastUserMessage.author()).append(": ").append(lastUserMessage.text()).append("\n");
            }
        }

        sb.append("\nReply as ").append(persona.displayName()).append(" to the last message (if it is a user message).\n");
        sb.append("Keep it concise (1-3 short paragraphs). No markdown.\n");
        return sb.toString();
    }

    /**
     * Baut das Request-JSON fuer die Gemini {@code generateContent}-API.
     *
     * <p>System-Prompt wird als {@code systemInstruction} gesetzt; die eigentliche Chat-Anweisung als "user" Content.
     */
    private static String buildJsonPayload(String systemPrompt, String userText) {
        return "{"
                + "\"systemInstruction\":{\"parts\":[{\"text\":\"" + jsonEscape(systemPrompt) + "\"}]},"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"" + jsonEscape(userText) + "\"}]}],"
                + "\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":256}"
                + "}";
    }

    /**
     * Extrahiert den ersten {@code text}-Block aus dem Response-JSON.
     *
     * <p>Minimal-Parser: findet die erste Vorkommnis von {@code "text":"..."} und unescaped JSON-Sequenzen.
     */
    private static String extractFirstText(String json) {
        Matcher m = TEXT_FIELD.matcher(json);
        if (!m.find()) return null;
        return jsonUnescape(m.group(1));
    }

    private static String prop(Properties p, String key) {
        return prop(p, key, null);
    }

    /**
     * Liesst einen Konfigurationswert.
     *
     * <p>Prioritaet:
     * <ol>
     *   <li>JVM System Property ({@code -Dkey=value})</li>
     *   <li>Properties-Datei</li>
     *   <li>Default</li>
     * </ol>
     */
    private static String prop(Properties p, String key, String def) {
        String v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v;
        v = p.getProperty(key);
        if (v != null && !v.isBlank()) return v;
        return def;
    }

    private static int readIntProp(Properties p, String key, int def, int min, int max) {
        String raw = prop(p, key, Integer.toString(def));
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                log.warn("Config {} out of range ({}), using default {}", key, raw, def);
                return def;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("Config {} invalid ({}), using default {}", key, raw, def);
            return def;
        }
    }

    private static long readLongProp(Properties p, String key, long def, long min, long max) {
        String raw = prop(p, key, Long.toString(def));
        try {
            long value = Long.parseLong(raw.trim());
            if (value < min || value > max) {
                log.warn("Config {} out of range ({}), using default {}", key, raw, def);
                return def;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("Config {} invalid ({}), using default {}", key, raw, def);
            return def;
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private long computeRetryDelayMs(int attempt, String retryAfterHeader) {
        Long retryAfterMs = parseRetryAfterMillis(retryAfterHeader);
        if (retryAfterMs != null) {
            return clamp(retryAfterMs, 0L, maxBackoffMs);
        }

        long delay = initialBackoffMs;
        for (int i = 1; i < attempt && delay < maxBackoffMs; i++) {
            long doubled = delay > (Long.MAX_VALUE / 2) ? Long.MAX_VALUE : delay * 2;
            delay = Math.min(maxBackoffMs, doubled);
        }
        long jitterMax = Math.max(25L, delay / 4);
        long jitter = ThreadLocalRandom.current().nextLong(jitterMax + 1);
        return Math.min(maxBackoffMs, delay + jitter);
    }

    private static Long parseRetryAfterMillis(String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isBlank()) return null;
        String raw = retryAfterHeader.trim();

        try {
            long seconds = Long.parseLong(raw);
            if (seconds < 0) return null;
            return seconds * 1_000L;
        } catch (NumberFormatException ignored) {
            // Fallback auf HTTP-date.
        }

        try {
            Instant when = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long waitMs = Duration.between(Instant.now(), when).toMillis();
            return Math.max(0L, waitMs);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean sleepForRetry(long delayMs) {
        try {
            Thread.sleep(Math.max(0L, delayMs));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gemini retry sleep interrupted: {}", e.toString());
            return false;
        }
    }

    /**
     * Escaped einen String fuer JSON-Stringliterale (ohne umliegende Quotes).
     */
    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Unescaped Standard JSON-Sequenzen ({@code \\n}, {@code \\uXXXX}, ...).
     */
    private static String jsonUnescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= s.length()) {
                out.append('\\');
                break;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        String hex = s.substring(i + 1, i + 5);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append("\\u").append(hex);
                            i += 4;
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }
}
