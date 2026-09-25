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
 * {@link AIResponder}-Implementierung, die OpenAI Chat Completions via HTTP aufruft.
 *
 * <p>Konfiguration:
 * <ul>
 *   <li>{@code openai.api.key}: API-Key (ueblich via {@code OPENAI_API_KEY})</li>
 *   <li>{@code openai.base.url}: Default {@code https://api.openai.com/v1}</li>
 *   <li>{@code openai.model}: Default {@code gpt-4.1-mini}</li>
 *   <li>{@code openai.retry.max.attempts}: Anzahl Versuche fuer temporaere Fehler (Default {@code 3})</li>
 *   <li>{@code openai.retry.initial.delay.ms}: initialer Retry-Backoff in ms (Default {@code 500})</li>
 *   <li>{@code openai.retry.max.delay.ms}: maximaler Retry-Backoff in ms (Default {@code 5000})</li>
 * </ul>
 *
 * <p>Hinweis: Das JSON-Parsing ist absichtlich simpel (Regex auf {@code choices[0].message.content}).
 */
public final class RealOpenAiResponder implements AIResponder {
    private static final Logger log = LogManager.getLogger(RealOpenAiResponder.class);
    private static final Pattern ASSISTANT_CONTENT =
            Pattern.compile("\"message\"\\s*:\\s*\\{.*?\"content\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);

    private final HttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    /**
     * Baut einen Responder aus geladenen Properties.
     *
     * @param props Properties (z.B. aus {@code application.properties}), ggf. schon mit Env-Variablen aufgeloest
     */
    public RealOpenAiResponder(Properties props) {
        Objects.requireNonNull(props);
        this.apiKey = prop(props, "openai.api.key");
        this.baseUrl = prop(props, "openai.base.url", "https://api.openai.com/v1");
        this.model = prop(props, "openai.model", "gpt-4.1-mini");
        this.maxAttempts = readIntProp(props, "openai.retry.max.attempts", 3, 1, 10);
        this.initialBackoffMs = readLongProp(props, "openai.retry.initial.delay.ms", 500L, 50L, 120_000L);
        this.maxBackoffMs = readLongProp(props, "openai.retry.max.delay.ms", 5_000L, this.initialBackoffMs, 300_000L);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String respond(Persona persona, List<ChatMessage> history, ChatMessage lastUserMessage) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.warn("OpenAI API key missing (set OPENAI_API_KEY env var or -Dopenai.api.key=...)");
            return "[OpenAI API key missing: set OPENAI_API_KEY env var]";
        }

        String prompt = buildPrompt(persona, history, lastUserMessage);
        String payload = buildJsonPayload(model, persona.systemPrompt(), prompt);
        String endpointBase = normalizeEndpointBase(baseUrl);
        String url = endpointBase + "/chat/completions";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = res.statusCode();
                if (status / 100 == 2) {
                    String text = extractAssistantText(res.body());
                    if (text == null) {
                        log.warn("OpenAI parse error (response starts with: {})", abbreviate(res.body(), 300));
                        return "[OpenAI parse error]";
                    }
                    return text.trim();
                }

                if (isRetryableStatus(status) && attempt < maxAttempts) {
                    long delayMs = computeRetryDelayMs(attempt, res.headers().firstValue("Retry-After").orElse(null));
                    log.warn(
                            "OpenAI HTTP {} (body starts with: {}), retry in {} ms (attempt {}/{})",
                            status, abbreviate(res.body(), 300), delayMs, attempt, maxAttempts
                    );
                    if (!sleepForRetry(delayMs)) {
                        return "[OpenAI error: InterruptedException]";
                    }
                    continue;
                }

                log.warn("OpenAI HTTP {} (body starts with: {})", status, abbreviate(res.body(), 300));
                return "[OpenAI HTTP " + status + "]";
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    long delayMs = computeRetryDelayMs(attempt, null);
                    log.warn("OpenAI request failed: {}, retry in {} ms (attempt {}/{})",
                            e.toString(), delayMs, attempt, maxAttempts);
                    if (!sleepForRetry(delayMs)) {
                        return "[OpenAI error: InterruptedException]";
                    }
                    continue;
                }
                log.warn("OpenAI request failed: {}", e.toString());
                return "[OpenAI error: " + e.getClass().getSimpleName() + "]";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("OpenAI request interrupted: {}", e.toString());
                return "[OpenAI error: InterruptedException]";
            } catch (Exception e) {
                log.warn("OpenAI error", e);
                return "[OpenAI error]";
            }
        }

        return "[OpenAI error]";
    }

    private static String normalizeEndpointBase(String rawBaseUrl) {
        String root = (rawBaseUrl == null) ? "" : rawBaseUrl.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (root.endsWith("/v1")) return root;
        if (root.isEmpty()) return "https://api.openai.com/v1";
        return root + "/v1";
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

    private static String buildJsonPayload(String model, String systemPrompt, String userPrompt) {
        return "{"
                + "\"model\":\"" + jsonEscape(model) + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + jsonEscape(systemPrompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + jsonEscape(userPrompt) + "\"}"
                + "],"
                + "\"temperature\":0.7,"
                + "\"max_completion_tokens\":256"
                + "}";
    }

    private static String extractAssistantText(String json) {
        Matcher m = ASSISTANT_CONTENT.matcher(json);
        if (!m.find()) return null;
        return jsonUnescape(m.group(1));
    }

    private static String prop(Properties p, String key) {
        return prop(p, key, null);
    }

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
            log.warn("OpenAI retry sleep interrupted: {}", e.toString());
            return false;
        }
    }

    private static String abbreviate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, Math.max(0, maxChars)) + "...";
    }

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
