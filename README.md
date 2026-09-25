# ai-chat-system

Ein minimalistischer Mehrbenutzer-Chatraum auf Basis von TCP-Sockets (Java 21, nur Standardbibliothek,
keine Frameworks) mit einem AI-Bot-Client, der als Persona dem Chat beitritt und auf Nachrichten
antwortet (OpenAI oder Google Gemini).

Ausfuehrliche Architektur-Dokumentation (arc42) und die urspruengliche Aufgabenstellung liegen in
[`docs/Arc42.md`](docs/Arc42.md) und [`docs/Anforderungen.md`](docs/Anforderungen.md). Hinweise fuer die
Arbeit mit Claude Code an diesem Repository finden sich in [`CLAUDE.md`](CLAUDE.md).
Generiertes Javadoc liegt unter `docs/javadoc/index.html` und ist online lesbar unter
https://larlibu.github.io/ai-chat-system/javadoc/ (GitHub Pages, Branch `main`, Ordner `/docs`).



## Features

- TCP-Chatserver, der Nachrichten an alle verbundenen Clients broadcastet (keine Server-seitige Historie)
- Interaktiver Konsolen-Client fuer menschliche Nutzer
- AI-Bot-Client mit wählbarer Persona:
  - `tom_sawyer` – rustikaler Erzaehler mit trockenem Humor
  - `albert_zweistein` – neugieriger Erklaerer mit Denkexperimenten
  - `?` – "Mystery Guest": laesst die AI ad hoc eine neue, zufaellige Persona erfinden
- Anbindung an OpenAI (Chat Completions) oder Google Gemini per HTTP, inkl. Retry/Backoff bei
  temporaeren Fehlern
- Soft Shutdown: AI-Clients verabschieden sich im Chat, statt abrupt die Verbindung zu trennen
- Jeder Client fuehrt seine eigene lokale Chat-Historie als Kontext fuer AI-Antworten

## Voraussetzungen

- Java 21
- Maven

## Bauen

```
mvn -DskipTests package
```

Erzeugt u. a. eine ausfuehrbare Fat-JAR: `target/ai-chat-system-1.0.0-shaded.jar`.

## Starten

Die Anwendung kennt drei Modi:

```
java -jar target/ai-chat-system-1.0.0-shaded.jar server
java -jar target/ai-chat-system-1.0.0-shaded.jar human <host> <port>
java -jar target/ai-chat-system-1.0.0-shaded.jar ai [personaId|?] <host> <port>
```

- `server` startet den Chat-Server (TCP-Listener)
- `human <host> <port>` startet einen interaktiven Konsolen-Client
- `ai [personaId|?] <host> <port>` startet einen Bot-Client; wird `personaId` weggelassen, erfolgt eine
  interaktive Auswahl im AI-Fenster

Im Human-Client beendet `/quit` die Sitzung; der Server kann per `Ctrl+C` gestoppt werden.

### Alles auf einmal starten (Windows)

`start-all.ps1` baut bei Bedarf neu und startet Server, Human-Client und AI-Client jeweils in einem
eigenen, betitelten PowerShell-Fenster (optional automatisch angeordnet):

```
./start-all.ps1 -HostName localhost -Port 9999 -Provider openai -Persona tom_sawyer
```

Parameter:

| Parameter    | Beschreibung                                                              |
|--------------|----------------------------------------------------------------------------|
| `-HostName`  | Server-Host (Default `localhost`)                                        |
| `-Port`      | Server-Port (Default `9999`)                                             |
| `-Provider`  | `gemini` oder `openai` (Default `openai`)                                |
| `-Persona`   | `tom_sawyer`, `albert_zweistein`, `?` oder leer fuer interaktive Auswahl |
| `-GeminiKey` | Optional; sonst wird `$env:GEMINI_KEY` verwendet                         |
| `-OpenAiKey` | Optional; sonst wird `$env:OPENAI_API_KEY` verwendet                     |
| `-NoArrange` | Deaktiviert automatisches Anordnen der Fenster (Default aktiviert)       |

## Konfiguration

Konfiguriert wird ueber `src/main/resources/application.properties`. Werte in der Form `${ENV_VAR}`
werden beim Start durch Umgebungsvariablen ersetzt. `GEMINI_KEY` bzw. `OPENAI_API_KEY` werden zusaetzlich
automatisch als `gemini.api.key` bzw. `openai.api.key` uebernommen.

Wichtige Properties:

```properties
ai.provider=openai              # oder gemini

gemini.api.key=${GEMINI_KEY}
gemini.base.url=https://generativelanguage.googleapis.com/v1beta
gemini.model=gemini-2.5-pro

openai.api.key=${OPENAI_API_KEY}
openai.base.url=https://api.openai.com/v1
openai.model=gpt-4.1-mini

server.port=9999
```

`ai.provider` kann auch per JVM-Systemproperty ueberschrieben werden, z. B. `-Dai.provider=gemini`.
Die API-Keys sollten stets per Umgebungsvariable gesetzt werden, nicht im Klartext in der Properties-Datei.

## Protokoll

Die Kommunikation zwischen Client und Server erfolgt zeilenbasiert:

| Richtung        | Format                              | Bedeutung                          |
|------------------|--------------------------------------|-------------------------------------|
| Client → Server | `NICK\|<nick>`                       | Nickname setzen/aendern             |
| Client → Server | `QUIT\|<nick>`                       | Geordnetes Verlassen des Chats      |
| Client ↔ Server | `MSG\|<epochMilli>\|<nick>\|<text>`  | Chat-Nachricht                      |
| Server → Client | `SYS\|<epochMilli>\|<text>`          | Systemmeldung (Join/Leave/Rename …) |

Sonderzeichen (`\`, `|`, Zeilenumbrueche) werden escaped, damit jede Nachricht in einer Zeile bleibt.

## Architektur

Kurzueberblick der Pakete unter `de.larlibu.aichat` (MVC-artig, Constructor Injection statt Framework-DI):

- `factory` – Composition Root (`AppFactory`), laedt Konfiguration und verdrahtet die Komponenten
- `network` – `SocketServer`/`SocketClient` (TCP, ein virtueller Thread pro Verbindung)
- `service` – `ChatRoomManager`: verwaltet verbundene Clients und Broadcasts (Server-seitig)
- `controller` – `ChatController` (Human-REPL), `AiController` (Bot-Client)
- `ai` – `AIResponder`-Abstraktion mit `RealOpenAiResponder`/`RealGeminiResponder`, `AiPersonaFactory`
- `model` – `ChatMessage`, `Persona`, `ChatHistory` (client-lokale Historie)
- `view` – `CliView`: Konsolen-I/O-Abstraktion


