package com.eventlab.jobforge.api.panel;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job-forge.panel")
public class PanelProperties {

    /**
     * Conexiones simultáneas admitidas. El panel es público: sin tope, cada
     * pestaña abierta consume memoria del servidor indefinidamente.
     */
    private int maxSessions = 50;

    /**
     * Cada cuánto se envía la foto de las colas. Bajarlo hace el panel más vivo y
     * multiplica las consultas al broker por cada segundo y por cada instancia.
     */
    private Duration snapshotInterval = Duration.ofSeconds(1);

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public Duration getSnapshotInterval() {
        return snapshotInterval;
    }

    public void setSnapshotInterval(Duration snapshotInterval) {
        this.snapshotInterval = snapshotInterval;
    }
}
