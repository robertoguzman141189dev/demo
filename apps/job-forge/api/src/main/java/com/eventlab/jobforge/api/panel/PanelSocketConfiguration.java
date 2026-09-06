package com.eventlab.jobforge.api.panel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
@EnableConfigurationProperties(PanelProperties.class)
public class PanelSocketConfiguration implements WebSocketConfigurer {

    private final PanelBroadcaster broadcaster;

    public PanelSocketConfiguration(PanelBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // El panel se sirve desde otro origen mientras se desarrolla (ng serve en el
        // 4200). En F7, detrás del ingress, esto se acota al dominio real.
        registry.addHandler(broadcaster, "/ws/panel").setAllowedOriginPatterns("*");
    }
}
