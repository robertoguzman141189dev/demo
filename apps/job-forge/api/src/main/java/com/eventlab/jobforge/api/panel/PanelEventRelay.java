package com.eventlab.jobforge.api.panel;

import com.eventlab.jobforge.contracts.audit.AuditTopics;
import com.eventlab.jobforge.contracts.audit.JobEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reenvía al panel lo que va ocurriendo, leyéndolo del log de auditoría.
 *
 * <p>El panel no observa a los workers: observa el log que los workers escriben.
 * Por eso ve lo mismo aunque haya veinte réplicas, y por eso la vista es la misma
 * historia que quedará guardada.
 *
 * <p>El grupo de consumidores lleva un sufijo aleatorio a propósito. Un grupo
 * compartido repartiría los eventos entre las instancias del API y cada panel
 * vería solo un trozo de lo que pasa; para difundir hace falta que cada instancia
 * sea su propio grupo. Es justo lo contrario de lo que quiere el proyector, que sí
 * comparte grupo porque reparte trabajo.
 */
@Component
public class PanelEventRelay {

    private final PanelBroadcaster broadcaster;

    public PanelEventRelay(PanelBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @KafkaListener(topics = AuditTopics.EVENTS, groupId = "job-forge-panel-#{T(java.util.UUID).randomUUID()}")
    public void onEvent(JobEvent event) {
        broadcaster.broadcast(new PanelEvent("event", event));
    }

    public record PanelEvent(String type, JobEvent event) {
    }
}
