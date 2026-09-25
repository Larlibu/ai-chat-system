package de.larlibu.aichat.factory;

import de.larlibu.aichat.ai.AIResponder;
import de.larlibu.aichat.ai.AiPersonaFactory;
import de.larlibu.aichat.ai.RealGeminiResponder;
import de.larlibu.aichat.ai.RealOpenAiResponder;
import de.larlibu.aichat.controller.AiController;
import de.larlibu.aichat.controller.ChatController;
import de.larlibu.aichat.model.ChatHistory;
import de.larlibu.aichat.network.SocketClient;
import de.larlibu.aichat.network.SocketServer;
import de.larlibu.aichat.service.ChatRoomManager;
import de.larlibu.aichat.view.CliView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erzeugt alle Hauptkomponenten (Server/Human-Client/AI-Client) und laedt Konfiguration.
 *
 * <p>Konfiguration kommt aus {@code application.properties} im Classpath und kann Env-Variablen enthalten
 * (Syntax {@code ${NAME}}). Zusaetzlich werden {@code GEMINI_KEY} und {@code OPENAI_API_KEY} als
 * Shortcuts auf {@code gemini.api.key} bzw. {@code openai.api.key} gesetzt.
 */
public final class AppFactory {
    private static final Logger log = LogManager.getLogger(AppFactory.class);
    private final Properties props;

    /**
     * Laedt Properties einmalig und haelt sie fuer die Erzeugung der Komponenten vor.
     */
    public AppFactory() {
        this.props = loadAndResolveProperties();
        logEffectiveConfig();
    }

    /**
     * Erzeugt den Chat-Server.
     *
     * @return Server-Instanz (noch nicht gestartet)
     */
    public SocketServer createServer() {
        int port = Integer.parseInt(props.getProperty("server.port", "9999"));
        ChatRoomManager room = new ChatRoomManager();
        return new SocketServer(port, room);
    }

    /**
     * Erzeugt einen interaktiven Human-Client.
     *
     * @param host Server-Host
     * @param port Server-Port
     * @return Controller fuer den interaktiven Human-Client (noch nicht gestartet)
     */
    public ChatController createHumanClient(String host, int port) {
        ChatHistory model = new ChatHistory.InMemoryChatHistory();
        CliView view = createCliView();
        SocketClient socket = new SocketClient(host, port);
        return new ChatController(model, view, socket);
    }

    /**
     * Erzeugt die Standard-CLI-View.
     *
     * @return View fuer Konsolen-Ein/Ausgabe
     */
    public CliView createCliView() {
        return new CliView.DefaultCliView();
    }

    /**
     * Erzeugt den konfigurierten AI-Responder.
     *
     * @return Responder fuer externe AI-Aufrufe
     */
    public AIResponder createAiResponder() {
        String provider = resolveAiProvider();
        return switch (provider) {
            case "openai" -> new RealOpenAiResponder(props);
            case "gemini" -> new RealGeminiResponder(props);
            default -> {
                log.warn("Unknown ai.provider='{}', fallback to gemini", provider);
                yield new RealGeminiResponder(props);
            }
        };
    }

    /**
     * Erzeugt die Persona-Factory fuer feste und dynamische AI-Personas.
     *
     * @return Persona-Factory
     */
    public AiPersonaFactory createPersonaFactory() {
        return new AiPersonaFactory(createAiResponder());
    }

    /**
     * Erzeugt einen AI-Bot-Client.
     *
     * @param personaId Persona-ID (siehe CLI Usage)
     * @param host Server-Host
     * @param port Server-Port
     * @return Controller fuer den AI-Bot-Client (noch nicht gestartet)
     */
    public AiController createAiClient(String personaId, String host, int port) {
        ChatHistory model = new ChatHistory.InMemoryChatHistory();
        CliView view = createCliView();
        SocketClient socket = new SocketClient(host, port);

        AIResponder responder = createAiResponder();
        AiPersonaFactory personaFactory = new AiPersonaFactory(responder);
        return new AiController(model, view, socket, responder, personaFactory, personaId);
    }

    /**
     * Laedt {@code application.properties} und ersetzt Platzhalter der Form {@code ${ENV_VAR}} mit
     * Werten aus der Umgebung. Fehlende Env-Vars werden durch leeren String ersetzt.
     *
     * <p>Zusaetzlich: Wenn {@code GEMINI_KEY} gesetzt ist, wird es als {@code gemini.api.key} uebernommen.
     * Wenn {@code OPENAI_API_KEY} gesetzt ist, wird es als {@code openai.api.key} uebernommen.
     */
    private static Properties loadAndResolveProperties() {
        Properties p = new Properties();
        try (InputStream in = AppFactory.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
        }

        Pattern env = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
        for (String name : p.stringPropertyNames()) {
            String val = p.getProperty(name);
            if (val == null) continue;
            Matcher m = env.matcher(val);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String key = m.group(1);
                String repl = System.getenv(key);
                if (repl == null) repl = "";
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
                changed = true;
            }
            if (changed) {
                m.appendTail(sb);
                p.setProperty(name, sb.toString());
            }
        }

        String gem = System.getenv("GEMINI_KEY");
        if (gem != null && !gem.isBlank()) {
            p.setProperty("gemini.api.key", gem);
        }
        String openAi = System.getenv("OPENAI_API_KEY");
        if (openAi != null && !openAi.isBlank()) {
            p.setProperty("openai.api.key", openAi);
        }
        return p;
    }

    private String resolveAiProvider() {
        String configured = System.getProperty("ai.provider");
        if (configured == null || configured.isBlank()) {
            configured = props.getProperty("ai.provider", "gemini");
        }
        return configured.trim().toLowerCase(Locale.ROOT);
    }

    private void logEffectiveConfig() {
        try {
            String port = props.getProperty("server.port", "9999");
            String provider = resolveAiProvider();
            String baseUrl = props.getProperty("gemini.base.url", "");
            String model = props.getProperty("gemini.model", "");
            String key = props.getProperty("gemini.api.key", "");
            String retryAttempts = props.getProperty("gemini.retry.max.attempts", "");
            String retryInitialDelay = props.getProperty("gemini.retry.initial.delay.ms", "");
            String retryMaxDelay = props.getProperty("gemini.retry.max.delay.ms", "");

            String openAiBaseUrl = props.getProperty("openai.base.url", "");
            String openAiModel = props.getProperty("openai.model", "");
            String openAiKey = props.getProperty("openai.api.key", "");
            String openAiRetryAttempts = props.getProperty("openai.retry.max.attempts", "");
            String openAiRetryInitialDelay = props.getProperty("openai.retry.initial.delay.ms", "");
            String openAiRetryMaxDelay = props.getProperty("openai.retry.max.delay.ms", "");

            boolean hasGeminiKey = key != null && !key.isBlank() && !key.startsWith("${");
            boolean hasOpenAiKey = openAiKey != null && !openAiKey.isBlank() && !openAiKey.startsWith("${");

            log.info("Config: server.port={}", port);
            log.info("Config: ai.provider={}", provider);
            if (!baseUrl.isBlank()) log.info("Config: gemini.base.url={}", baseUrl);
            if (!model.isBlank()) log.info("Config: gemini.model={}", model);
            if (!retryAttempts.isBlank()) log.info("Config: gemini.retry.max.attempts={}", retryAttempts);
            if (!retryInitialDelay.isBlank()) log.info("Config: gemini.retry.initial.delay.ms={}", retryInitialDelay);
            if (!retryMaxDelay.isBlank()) log.info("Config: gemini.retry.max.delay.ms={}", retryMaxDelay);
            log.info("Config: gemini.api.key={}", hasGeminiKey ? "<set>" : "<missing>");

            if (!openAiBaseUrl.isBlank()) log.info("Config: openai.base.url={}", openAiBaseUrl);
            if (!openAiModel.isBlank()) log.info("Config: openai.model={}", openAiModel);
            if (!openAiRetryAttempts.isBlank()) log.info("Config: openai.retry.max.attempts={}", openAiRetryAttempts);
            if (!openAiRetryInitialDelay.isBlank()) log.info("Config: openai.retry.initial.delay.ms={}", openAiRetryInitialDelay);
            if (!openAiRetryMaxDelay.isBlank()) log.info("Config: openai.retry.max.delay.ms={}", openAiRetryMaxDelay);
            log.info("Config: openai.api.key={}", hasOpenAiKey ? "<set>" : "<missing>");
        } catch (Exception e) {
            // Config logging must not break app start.
            log.debug("Failed to log config", e);
        }
    }
}
