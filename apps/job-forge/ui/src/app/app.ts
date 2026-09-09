import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Journey, PanelService } from './panel.service';

@Component({
  selector: 'app-root',
  imports: [FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly panel = inject(PanelService);

  protected readonly tasks = signal(12);
  protected readonly failProbability = signal(40);
  protected readonly priority = signal('NORMAL');
  protected readonly busy = signal(false);
  protected readonly message = signal('');

  /** El orden en que se pintan las colas: es el camino que recorre una tarea. */
  protected readonly queueOrder = [
    'jobs.work',
    'jobs.retry.5s',
    'jobs.retry.30s',
    'jobs.retry.2m',
    'jobs.dead',
  ];

  /**
   * La tarea cuyo viaje se está mirando.
   *
   * null significa "sigue a la última que se mueva", que es lo que quieres al
   * abrir el panel: no hay que elegir nada para ver algo. En cuanto pulsas una,
   * se queda fija, porque si no sería imposible leerla con tráfico entrando.
   */
  protected readonly pinnedTask = signal<string | null>(null);

  protected readonly journey = computed<Journey | undefined>(() => {
    const journeys = this.panel.journeys();
    const pinned = this.pinnedTask();
    return pinned ? journeys.find((j) => j.taskId === pinned) : journeys[0];
  });

  ngOnInit(): void {
    this.panel.connect();
    this.panel.refreshDeadLetters();
  }

  protected depth(queue: string): number {
    return this.panel.queues()[queue] ?? 0;
  }

  /** Pulsar la tarea ya fijada la suelta: es el mismo gesto para las dos cosas. */
  protected pin(taskId: string): void {
    this.pinnedTask.update((current) => (current === taskId ? null : taskId));
  }

  /**
   * La espera entre dos pasos, que es el dato que hace visible el escalonado.
   *
   * Se redondea a una decimal en segundos y se pasa a minutos por encima de 90 s:
   * "120,0 s" obliga a hacer la división mentalmente, y "2,0 min" no.
   */
  protected formatWait(ms: number): string {
    if (ms < 1000) {
      return `${ms} ms`;
    }
    const seconds = ms / 1000;
    if (seconds < 90) {
      return `${seconds.toFixed(1)} s`.replace('.', ',');
    }
    return `${(seconds / 60).toFixed(1)} min`.replace('.', ',');
  }

  /** La hora del evento, sin fecha: el viaje entero cabe en unos minutos. */
  protected formatTime(iso: string): string {
    return new Date(iso).toLocaleTimeString('es-ES', { hour12: false });
  }

  /**
   * Traduce el tipo de evento a algo que se lea. Los nombres del log son
   * constantes de Java y están bien ahí, pero no en una pantalla.
   */
  protected label(type: string): string {
    switch (type) {
      case 'SUBMITTED':
        return 'aceptada';
      case 'RETRY_SCHEDULED':
        return 'a la cola de espera';
      case 'COMPLETED':
        return 'completada';
      case 'DEAD_LETTERED':
        return 'a la cola de muertos';
      case 'DUPLICATE_DISCARDED':
        return 'duplicado descartado';
      case 'REPROCESSED':
        return 'devuelta al circuito';
      default:
        return type.toLowerCase();
    }
  }

  protected generate(): void {
    this.busy.set(true);
    this.panel
      .generate(this.tasks(), this.failProbability(), this.priority())
      .subscribe({
        next: () => this.done(`${this.tasks()} tareas encoladas`),
        error: (e) => this.done(`error: ${e.error?.message ?? e.message}`),
      });
  }

  protected upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.busy.set(true);
    this.panel.upload(file, this.failProbability(), this.priority()).subscribe({
      next: () => this.done(`archivo ${file.name} descompuesto en tareas`),
      error: (e) => this.done(`error: ${e.error?.message ?? e.message}`),
    });
    input.value = '';
  }

  protected reprocess(): void {
    this.busy.set(true);
    this.panel.reprocessDeadLetters().subscribe({
      next: (result) => {
        this.done(`${result.reprocessed} tareas devueltas al circuito`);
        this.panel.refreshDeadLetters();
      },
      error: (e) => this.done(`error: ${e.message}`),
    });
  }

  private done(text: string): void {
    this.busy.set(false);
    this.message.set(text);
  }
}
