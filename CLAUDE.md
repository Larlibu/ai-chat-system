# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A minimal multi-user TCP chat room (Java 21, stdlib only, no frameworks) with an AI bot client that
joins as a persona and replies to human messages via OpenAI or Gemini. Three run modes share one JAR:
`server`, `human` (interactive console client), `ai` (bot client). Built as a school project (see
`docs/Anforderungen.md` for the original task description and `docs/Arc42.md` for the arc42 architecture
doc — keep both in sync with structural changes).

## Build & run

```
mvn -DskipTests package          # builds target/ai-chat-system-1.0.0-shaded.jar (fat jar, Main-Class set)
mvn test                         # no test sources currently exist in src/test
```

Run the shaded jar directly:

```
java -jar target/ai-chat-system-1.0.0-shaded.jar server
java -jar target/ai-chat-system-1.0.0-shaded.jar human <host> <port>
java -jar target/ai-chat-system-1.0.0-shaded.jar ai [personaId|?] <host> <port>
```

`./start-all.ps1` (Windows PowerShell) builds if stale and launches server + human + ai each in their own
titled console window (optionally auto-arranged on screen via Win32 interop). Params: `-HostName`, `-Port`,
`-Provider` (`gemini`|`openai`), `-Persona` (`tom_sawyer`|`albert_zweistein`|`?`|empty for interactive),
`-GeminiKey`, `-OpenAiKey`, `-NoArrange`.

There is no `src/test` directory — no test framework is wired up yet.

## Architecture

Classic composition-root / layered (MVC-flavored) structure, everything under `de.larlibu.aichat`:

- **`factory.AppFactory`** — the composition root. Loads `application.properties` from the classpath,
  resolves `${ENV_VAR}` placeholders against the real environment, and is the single place that wires up
  Server/ChatController/AiController with their dependencies. `GEMINI_KEY`/`OPENAI_API_KEY` env vars are
  shortcuts that override `gemini.api.key`/`openai.api.key`. `ai.provider` (property or `-D` system property)
  picks which `AIResponder` gets constructed.
- **`network`** — `SocketServer` (blocking accept loop, one virtual thread per client via
  `Executors.newVirtualThreadPerTaskExecutor()`) and `SocketClient` (connects, spins a virtual-thread read
  loop, delivers lines via a `Consumer<String>` callback). The server does not store history; it only
  broadcasts. Wire protocol is line-based: `NICK|<nick>`, `QUIT|<nick>`, `MSG|<epochMilli>|<nick>|<text>`
  (client→server), `SYS|<epochMilli>|<text>` (server→clients). Pipe/backslash/newlines are escaped
  (`\|`→`\p`, `\n`, `\r`, `\\`); encode/decode logic is duplicated in `SocketServer` (system messages) and
  `ChatController.Protocol` (full protocol, reused by `AiController`) — keep both in sync if the wire format
  changes.
- **`service.ChatRoomManager`** — server-side: holds connected clients in a `ConcurrentHashMap`-backed set,
  broadcasts raw lines to all of them. No persistence.
- **`controller`** — `ChatController` is the blocking human REPL (reads stdin, sends `MSG`/`QUIT`).
  `AiController` connects the same way but never reads stdin; on every incoming `USER` message (that isn't
  its own, matched by display-name-as-nickname) it builds a reply via `AIResponder.respond(...)` using its
  local `ChatHistory` snapshot as context, and sends it back as a normal chat message. Both register a JVM
  shutdown hook to send `QUIT` and flush the socket before the process dies (important for Windows
  console-close events) — this implements the "Soft Shutdown" requirement.
- **`ai`** — `AIResponder` is the abstraction (`respond(Persona, history, lastUserMessage) -> String`).
  `RealOpenAiResponder` / `RealGeminiResponder` are HTTP implementations with manual retry/backoff
  (`Retry-After`-aware) and deliberately simple regex-based JSON extraction (no JSON library dependency).
  `AiPersonaFactory` builds `Persona` records for the two fixed personas (`tom_sawyer`, `albert_zweistein`)
  and for `"?"` — the "Mystery Guest" — which prompts the configured `AIResponder` itself to invent a new
  persona (parses a `NAME: ...\nPROMPT: ...` response format, with a hardcoded fallback persona if parsing
  fails).
- **`model`** — plain records/interfaces: `ChatMessage` (USER/SYSTEM), `Persona`, `ChatHistory` (client-local
  message log; the server keeps none — each client has its own view; `InMemoryChatHistory` is synchronized
  since messages arrive on the socket reader's virtual thread).
- **`view.CliView`** — thin console I/O abstraction (`DefaultCliView` wraps stdin/stdout) separating
  controllers from concrete I/O.

Config resolution order for AI responders (`RealOpenAiResponder`/`RealGeminiResponder`): a `-D` JVM system
property wins, then `application.properties`, then the hardcoded default. Logging is Log4j2, configured via
`src/main/resources/log4j2.xml`, writing to `logs/`.

## Docs

- `docs/Anforderungen.md` — original assignment/requirements (German)
- `docs/Arc42.md` — arc42 architecture documentation (German), including package structure and mermaid
  diagrams for context, building blocks, and runtime scenarios
- `docs/javadoc` — generated Javadoc (entry point: `docs/javadoc/index.html`); regenerate via
  `mvn javadoc:javadoc` (writes to `target/reports/apidocs`) and copy into `docs/javadoc`, since the plugin
  goal always appends an `apidocs` subfolder and has no config to avoid that
