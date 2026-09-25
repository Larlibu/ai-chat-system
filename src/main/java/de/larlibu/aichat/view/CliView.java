package de.larlibu.aichat.view;

import de.larlibu.aichat.model.ChatMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Sehr einfache View-Abstraktion fuer Konsolen-Interaktion.
 *
 * <p>Trennt Controller-Logik (Chat/Ai) von konkreter Ein-/Ausgabe, damit man spaeter einfacher Tests oder
 * andere UIs anbinden kann.
 */
public interface CliView {
    /**
     * Liest eine Zeile vom User.
     *
     * @param prompt Prompt-Text, der vor der Eingabe ausgegeben wird
     * @return Zeile oder {@code null} bei EOF/Fehler
     */
    String readLine(String prompt);

    /**
     * Druckt eine Chat-Nachricht formatiert.
     *
     * @param message anzuzeigende Nachricht
     */
    void print(ChatMessage message);

    /**
     * Druckt System-Text (ohne ChatMessage-Formatierung).
     *
     * @param text anzuzeigender Text
     */
    void system(String text);

    /**
     * Default-Implementierung fuer die Konsole (stdin/stdout).
     */
    final class DefaultCliView implements CliView {
        private final BufferedReader in;
        private final PrintStream out;
        private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        /**
         * Verwendet {@code System.in}/{@code System.out} (UTF-8 Reader).
         */
        public DefaultCliView() {
            this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        }

        /**
         * @param in Input-Reader (z.B. fuer Tests)
         * @param out Output-Stream (z.B. fuer Tests)
         */
        public DefaultCliView(BufferedReader in, PrintStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public String readLine(String prompt) {
            try {
                out.print(prompt);
                out.flush();
                return in.readLine();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void print(ChatMessage message) {
            String ts = tsFmt.format(message.timestamp());
            if (message.type() == ChatMessage.Type.SYSTEM) {
                out.println("[" + ts + "] * " + message.text());
                return;
            }
            out.println("[" + ts + "] " + message.author() + ": " + message.text());
        }

        @Override
        public void system(String text) {
            out.println(text);
        }
    }
}
