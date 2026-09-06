package com.eventlab.jobforge.api.panel;

import java.util.Map;

/**
 * La foto que ve el panel cada segundo. Es lo que hace visible el mecanismo: las
 * colas de espera llenándose y vaciándose solas es el backoff escalonado ocurriendo
 * delante de quien mira.
 */
public record PanelSnapshot(String type, Map<String, Long> queues, int sessions) {

    public static PanelSnapshot of(Map<String, Long> queues, int sessions) {
        return new PanelSnapshot("snapshot", queues, sessions);
    }
}
