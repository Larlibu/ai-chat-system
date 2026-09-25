# Projekt

## 1. AI-Chat-Plattform

In diesem Projekt entwickelst du eine Client-Server-Anwendung für einen Chat, der über ein Command Line Interface (CLI) bedient wird und über integrierte AI-Teilnehmer verfügt.

Rahmenbedingungen:
- Keine externen Abhängigkeiten: Es dürfen keine externen Packages oder Libraries verwendet werden. Nutze ausschließlich die Standardbibliothek der gewählten Programmiersprache.
- Sprachwahl: Die Programmiersprache ist frei wählbar.
- AI-First: Nutze AI-Tools aktiv zur Unterstützung in allen Phasen der Entwicklung.


### Teilaufgaben

1. Recherche und Konzeption: Beginne mit einer fundierten Planungsphase. Nutze AI-Tools, um effizient Informationen über Client-Server-Architekturen zu sammeln und Best Practices für CLI-Anwendungen zu identifizieren.

- Erstelle basierend auf deiner Recherche ein technisches Konzept.
- Sammle alle Planungsmaterialien (Architekturskizzen, Sequenzdiagramme, technische Spezifikationen) im Ordner plan deines Repositories.


2. Implementierung von Server und Client: Entwickle den Chat-Server und den dazugehörigen Client unter Verwendung eines AI-First-Ansatzes.

- Funktionsumfang: Implementiere einen "Single-Room-Chat". Das bedeutet, jeder Client, der sich mit dem Server verbindet, landet automatisch im selben, globalen Chatraum.
- Code-Qualität: Achte strikt auf Wartbarkeit und Erweiterbarkeit des Codes. Vermeide "Vibe Coding" (unreflektiertes Zusammenkopieren von AI-Snippets); der Code muss sauber strukturiert und verstanden sein.


3. AI-Integration und Personas: Das Kernstück des Systems ist die Integration künstlicher Intelligenz (z. B. via Anbindung an die Gemini CLI oder eine vergleichbare API).

- Integration: Die AI soll als aktiver Teilnehmer im Chat agieren können.
- Konfiguration (AI-Clients): Implementiere eine Funktion, die es erlaubt, beim Start eines Clients spezifische AI-Persönlichkeiten auszuwählen, die dem Server beitreten. Diese AI-Clients agieren eigenständig und simulieren die gewählte Persönlichkeit, bis sie beendet werden.
- Soft Shutdown: Implementiere eine "Soft Shutdown"-Funktionalität. Wenn ein AI-Client beendet wird, soll die Verbindung nicht abrupt abbrechen, sondern die AI soll sich noch mit einer passenden Nachricht im Chat verabschieden.
- Prompt Engineering: Erstelle für jede AI-Persona eine detaillierte Hintergrundgeschichte (System Prompt). Je tiefergehend die Biografie definiert ist, desto authentischer ("plastischer") wirkt die Simulation der Persönlichkeit im Chat.

Beispiele für Personas:
- Tom Zawyer: Besitzt eine abgelegene Farm im Süden Mississippis. Er kämpft mit den Folgen von Viehdiebstahl und leidet unter der aktuellen Inflation. Sein Ton ist rustikal und bodenständig.
- Albert Zweistein: Ein ehrgeiziger Physikstudent, der sich fast ausschließlich mit Quantentheorien beschäftigt. Er neigt dazu, alltägliche Konversationen auf komplexe physikalische Phänomene zu beziehen.


4. Der "Mystery Guest" Generator: Erweitere die Auswahl der Persönlichkeiten um eine dynamische Komponente, die für Abwechslung sorgt.

- Das '?' Feature: Füge der Auswahl beim Start des Clients eine spezielle Option (z. B. "?") hinzu.
- Generierung: Wählt der Nutzer diese Option, soll der Client eine Anfrage an die AI stellen, um ad hoc eine völlig neue, zufällige Persona zu generieren. Die AI soll sich hierbei selbstständig einen Namen und eine kreative, einzigartige Hintergrundgeschichte ausdenken.
- Initialisierung: Nutze die Antwort der AI, um den Client direkt mit dieser neuen Identität zu initialisieren und dem Server beizutreten. Dies ermöglicht eine theoretisch unendliche Vielfalt an Chat-Partnern ohne manuelle Vorkonfiguration.


