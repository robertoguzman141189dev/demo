package com.eventlab.jobforge.api.panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * El canal en vivo del panel. Mantiene las sesiones abiertas y les empuja lo que
 * va pasando.
 *
 * <p>El panel es público y anónimo, así que esto es superficie de ataque. Tres
 * defensas, todas visibles aquí:
 *
 * <ul>
 *   <li><strong>Tope de sesiones.</strong> Pasado el límite se rechaza la conexión
 *       en vez de aceptarla y morir despacio. Cien pestañas abiertas son cien
 *       conexiones que alguien sostiene.</li>
 *   <li><strong>El servidor solo habla.</strong> Lo que llegue del cliente por el
 *       socket se ignora: las acciones van por HTTP, donde se pueden limitar y
 *       auditar. Un socket que acepta órdenes es un socket que hay que validar.</li>
 *   <li><strong>Al cliente lento se le cierra.</strong> Si un envío falla, se cierra
 *       la sesión en lugar de acumular mensajes que nadie lee.</li>
 * </ul>
 */
@Component
public class PanelBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PanelBroadcaster.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper json;
    private final int maxSessions;

    public PanelBroadcaster(ObjectMapper json, PanelProperties properties) {
        this.json = json;
        this.maxSessions = properties.getMaxSessions();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (sessions.size() >= maxSessions) {
            log.warn("panel lleno ({} sesiones): se rechaza una conexión nueva", maxSessions);
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, org.springframework.web.socket.TextMessage message) {
        // A propósito, nada. Este canal es de una sola dirección.
    }

    public void broadcast(Object payload) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage message;
        try {
            message = new TextMessage(json.writeValueAsString(payload));
        } catch (IOException e) {
            log.error("no se pudo serializar un mensaje del panel", e);
            return;
        }

        for (WebSocketSession session : sessions) {
            try {
                // synchronized porque una sesión no admite dos envíos a la vez, y
                // aquí pueden coincidir el reloj de instantáneas y un evento.
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("sesión del panel descartada: {}", e.getMessage());
                sessions.remove(session);
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException ignored) {
                    // Ya estaba rota; no hay nada que hacer.
                }
            }
        }
    }

    public int openSessions() {
        return sessions.size();
    }
}
