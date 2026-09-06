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
          // Se guardan los últimos 60 y se tiran los demás: una pestaña abierta
          // toda la tarde no puede acumular memoria sin límite.
          this.events.update((current) => [payload.event, ...current].slice(0, 60));
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
