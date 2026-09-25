/**
 * Minimaler Chatraum (Server + Clients) auf Basis von TCP-Sockets.
 *
 * <p>Dieses Projekt besteht aus drei Start-Modi:
 * <ul>
 *   <li>{@code server}: Startet einen TCP-Server und broadcastet Chat-Nachrichten an alle Clients.</li>
 *   <li>{@code human}: Startet einen Konsolen-Client fuer Menschen.</li>
 *   <li>{@code ai}: Startet einen Konsolen-Client, der automatisch mit einer Persona antwortet.</li>
 * </ul>
 *
 * <p>Die Architektur ist bewusst einfach gehalten (Java-Standardbibliothek, keine Frameworks).
 */
package de.larlibu.aichat;

