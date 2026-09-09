import { Injectable, signal, computed, NgZone, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface JobEvent {
  jobId: string;
  taskId: string;
  type: string;
  attempt: number;
  detail: string;
  occurredAt: string;
  source: string;
}

export interface DeadLetter {
  taskId: string;
  jobId: string;
  attempts: number;
  failureReason: string;
  payload: string;
}

/** Un paso del viaje, con lo que la tarea esperó antes de llegar aquí. */
export interface JourneyStep {
  event: JobEvent;
  /** Milisegundos desde el paso anterior. null en el primero. */
  waitedMs: number | null;
}

/**
 * El recorrido completo de una tarea.
 *
 * Es la vista que hace visible la tesis del demo. El feed cronológico dice que
 * hubo reintentos; esto dice CUÁNTO se esperó entre uno y otro, que es donde se
 * ve el escalonado: 5 s, luego 30 s, luego 2 min.
 */
export interface Journey {
  taskId: string;
  jobId: string;
  steps: JourneyStep[];
  outcome: 'pendiente' | 'completada' | 'muerta';
  /** Desde el primer evento conocido hasta el último. */
  totalMs: number;
}

/**
 * El estado del panel en vivo.
 *
 * El socket es de una sola dirección: por aquí solo se recibe. Las acciones van
 * por HTTP, que es donde se pueden limitar, medir y auditar. Un socket que acepta
 * órdenes es un socket que hay que validar entero.
 */
@Injectable({ providedIn: 'root' })
export class PanelService {
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);

  private socket?: WebSocket;
  private reconnectDelayMs = 1000;

  readonly connected = signal(false);
  readonly queues = signal<Record<string, number>>({});
  readonly events = signal<JobEvent[]>([]);
  readonly deadLetters = signal<DeadLetter[]>([]);

  readonly deadCount = computed(() => this.queues()['jobs.dead'] ?? 0);
  readonly waiting = computed(() =>
    Object.entries(this.queues())
      .filter(([name]) => name.startsWith('jobs.retry.'))
      .reduce((total, [, depth]) => total + depth, 0),
  );

  /**
   * Los eventos agrupados por tarea, cada grupo en orden cronológico.
   *
   * Se derivan del mismo búfer que alimenta el feed, así que **el viaje de una
   * tarea muy lenta puede quedar recortado por delante**: si entre su primer
   * evento y el último pasan más de los eventos que caben en el búfer, los
   * primeros ya se descartaron. Se acepta a cambio de no acumular memoria sin
   * límite en una pestaña abierta toda la tarde; el búfer se dimensionó para que
   * un viaje completo con los tres tramos entre poco tráfico quepa de sobra.
   */
  readonly journeys = computed<Journey[]>(() => {
    const byTask = new Map<string, JobEvent[]>();
    // events() viene del más nuevo al más viejo; se acumula tal cual y se ordena
    // después, que es más barato que insertar ordenado.
    for (const event of this.events()) {
      const found = byTask.get(event.taskId);
      if (found) {
        found.push(event);
      } else {
        byTask.set(event.taskId, [event]);
      }
    }

    const journeys: Journey[] = [];
    for (const [taskId, events] of byTask) {
      const ordered = [...events].sort(
        (a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt),
      );

      const steps: JourneyStep[] = ordered.map((event, index) => ({
        event,
        waitedMs:
          index === 0
            ? null
            : Date.parse(event.occurredAt) - Date.parse(ordered[index - 1].occurredAt),
      }));

      const last = ordered[ordered.length - 1];
      journeys.push({
        taskId,
        jobId: last.jobId,
        steps,
        outcome:
          last.type === 'DEAD_LETTERED'
            ? 'muerta'
            : last.type === 'COMPLETED' || last.type === 'DUPLICATE_DISCARDED'
              ? 'completada'
              : 'pendiente',
        totalMs: Date.parse(last.occurredAt) - Date.parse(ordered[0].occurredAt),
      });
    }

    // Las de actividad más reciente primero: es el orden en que alguien quiere
    // mirarlas.
    return journeys.sort((a, b) => {
      const ultimoA = a.steps[a.steps.length - 1].event.occurredAt;
      const ultimoB = b.steps[b.steps.length - 1].event.occurredAt;
      return Date.parse(ultimoB) - Date.parse(ultimoA);
    });
  });

  connect(): void {
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/panel`;
    this.socket = new WebSocket(url);

    this.socket.onopen = () => {
      this.zone.run(() => this.connected.set(true));
      this.reconnectDelayMs = 1000;
    };

    this.socket.onmessage = (message) => {
      const payload = JSON.parse(message.data);
      this.zone.run(() => {
        if (payload.type === 'snapshot') {
          this.queues.set(payload.queues);
        } else if (payload.type === 'event') {
          // Se guardan los últimos 200 y se tiran los demás: una pestaña abierta
          // toda la tarde no puede acumular memoria sin límite.
          //
          // Eran 60, y se subió al añadir la vista del viaje: un viaje con los
          // tres tramos son unos siete eventos, y con 60 en total el viaje que
          // se estaba mirando desaparecía en cuanto había algo de tráfico. 200
          // eventos son unos pocos kilobytes.
          this.events.update((current) => [payload.event, ...current].slice(0, 200));
        }
      });
    };

    this.socket.onclose = () => {
      this.zone.run(() => this.connected.set(false));
      // Reconexión con espera creciente: si el servidor está caído, insistir cada
      // 100 ms solo empeora las cosas cuando vuelva.
      setTimeout(() => this.connect(), this.reconnectDelayMs);
      this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 15000);
    };
  }

  generate(tasks: number, failProbability: number, priority: string) {
    return this.http.post(
      `/api/jobs/synthetic?tasks=${tasks}&failProbability=${failProbability}&priority=${priority}`,
      null,
    );
  }

  upload(file: File, failProbability: number, priority: string) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post(
      `/api/jobs?failProbability=${failProbability}&priority=${priority}`,
      form,
    );
  }

  refreshDeadLetters(): void {
    this.http
      .get<DeadLetter[]>('/api/dead-letters?limit=10')
      .subscribe((found) => this.deadLetters.set(found));
  }

  reprocessDeadLetters() {
    return this.http.post<{ reprocessed: number }>('/api/dead-letters/reprocess?limit=50', null);
  }
}
