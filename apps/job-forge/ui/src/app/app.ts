import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { JobEvent, Journey, PanelService } from './panel.service';

/** Una partícula viajando por una arista de la topología. */
interface Particle {
  id: number;
  /** Clase CSS de la arista, que es la que lleva el offset-path. */
  edge: string;
  /** Color: neutro, éxito, reintento o muerte. */
  tone: string;
  delayMs: number;
}

/**
 * Qué recorre cada evento en el diagrama.
 *
 * Un evento puede encender más de una arista: una tarea que se completa viaja de
 * la cola al worker Y deja su rastro en el log de Kafka. El retardo escalona el
 * segundo tramo para que se vea como un recorrido y no como dos destellos.
 */
const RECORRIDOS: Record<string, { edge: string; tone: string; delayMs: number }[]> = {
  SUBMITTED: [{ edge: 'api-work', tone: 'neutro', delayMs: 0 }],
  COMPLETED: [
    { edge: 'work-worker', tone: 'exito', delayMs: 0 },
    { edge: 'worker-kafka', tone: 'exito', delayMs: 700 },
  ],
  RETRY_SCHEDULED: [
    { edge: 'worker-retry', tone: 'reintento', delayMs: 0 },
    { edge: 'retry-exchange', tone: 'reintento', delayMs: 900 },
    { edge: 'worker-kafka', tone: 'reintento', delayMs: 300 },
  ],
  DEAD_LETTERED: [
    { edge: 'work-dead', tone: 'muerte', delayMs: 0 },
    { edge: 'worker-kafka', tone: 'muerte', delayMs: 300 },
  ],
  REPROCESSED: [{ edge: 'dead-exchange', tone: 'neutro', delayMs: 0 }],
  DUPLICATE_DISCARDED: [{ edge: 'worker-kafka', tone: 'exito', delayMs: 0 }],
};

/** Lo que tarda una partícula en recorrer su arista. Debe coincidir con el CSS. */
const DURACION_MS = 1100;

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

  /** Las partículas vivas ahora mismo en el diagrama. */
  protected readonly particles = signal<Particle[]>([]);
  private nextParticleId = 0;

  /** Tareas esperando en cualquiera de los tres tramos, para pintarlo en el nodo. */
  protected readonly waiting = computed(() => this.panel.waiting());

  /**
   * El pulso del sistema: tres cifras que se leen de lejos.
   *
   * Existe porque para saber si esto estaba tranquilo o ahogado había que leer
   * cinco números pequeños y sumarlos mentalmente. El estado de un sistema tiene
   * que verse antes de leerse.
   */
  protected readonly pulse = computed(() => ({
    enCola: this.depth('jobs.work'),
    esperando: this.panel.waiting(),
    muertas: this.depth('jobs.dead'),
    completadas: this.panel.events().filter((e) => e.type === 'COMPLETED').length,
  }));

  /**
   * Una sola palabra para el estado general, que es lo que de verdad quiere
   * saber quien llega: ¿esto está bien o hay algo roto?
   */
  protected readonly mood = computed(() => {
    const p = this.pulse();
    if (p.muertas > 0) return 'con bajas';
    if (p.enCola + p.esperando > 0) return 'trabajando';
    return 'en reposo';
  });

  constructor() {
    // Una animación por cada evento que llega. Se observa lastEvent y no la
    // lista: la lista cambia también al descartar los viejos, y eso dispararía
    // animaciones fantasma.
    effect(() => {
      const event = this.panel.lastEvent();
      if (event) {
        this.animate(event);
      }
    });
  }

  ngOnInit(): void {
    this.panel.connect();
    this.panel.refreshDeadLetters();
  }

  protected depth(queue: string): number {
    return this.panel.queues()[queue] ?? 0;
  }

  /**
   * El ancho de la barra, medido contra la cola más llena y no contra un número
   * fijo.
   *
   * Antes era `profundidad * 4` como porcentaje, así que **se saturaba a los 25
   * mensajes**: con 200 tareas encoladas, las cinco barras se veían idénticas y
   * la comparación entre tramos dejaba de significar nada. Relativo al máximo,
   * la proporción siempre se lee.
   */
  protected barWidth(queue: string): number {
    const max = Math.max(...this.queueOrder.map((q) => this.depth(q)), 1);
    return (this.depth(queue) / max) * 100;
  }

  private animate(event: JobEvent): void {
    const recorrido = RECORRIDOS[event.type];
    if (!recorrido) {
      return;
    }

    for (const tramo of recorrido) {
      const particle: Particle = { id: this.nextParticleId++, ...tramo };
      // Tope de partículas vivas: con una ráfaga de doscientas tareas, animarlas
      // todas no se entiende y además hunde el navegador. Se quedan las últimas.
      this.particles.update((current) => [...current, particle].slice(-30));

      setTimeout(
        () => this.particles.update((current) => current.filter((p) => p.id !== particle.id)),
        tramo.delayMs + DURACION_MS + 100,
      );
    }
  }

  /** Pulsar la tarea ya fijada la suelta: es el mismo gesto para las dos cosas. */
  protected pin(taskId: string): void {
    this.pinnedTask.update((current) => (current === taskId ? null : taskId));
  }

  /**
   * DÓNDE esperó la tarea, deducido del paso anterior.
   *
   * Esto no es un adorno: sin ello la vista miente. Una tarea puede esperar por
   * dos motivos que significan lo contrario, y pintados iguales se confunden:
   *
   *   tras "aceptada"            -> espera en jobs.work porque hay COLA. Se
   *                                 arregla escalando workers.
   *   tras "a la cola de espera" -> espera en un tramo de TTL. Eso es el BACKOFF
   *                                 funcionando, y es el diseño.
   *
   * La primera versión de esta vista las mostraba iguales, y en el demo real un
   * atasco de 39 segundos en la cola de trabajo parecía un backoff de 39
   * segundos. Justo el malentendido que la vista existe para evitar.
   */
  protected waitKind(previousType: string): 'cola' | 'backoff' {
    return previousType === 'RETRY_SCHEDULED' ? 'backoff' : 'cola';
  }

  protected waitExplanation(previousType: string): string {
    return this.waitKind(previousType) === 'backoff'
      ? 'en un tramo de espera — esto es el backoff'
      : 'en jobs.work, esperando un worker libre';
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

  /**
   * Descarga un archivo de ejemplo para quien quiera probar la subida.
   *
   * Se genera en el navegador en vez de servirse como fichero estático: así no
   * hay una ruta más que mantener ni un archivo que se desincronice de los
   * límites reales del API. Veinte líneas, muy por debajo del tope de 200.
   */
  protected downloadSample(): void {
    const lineas = Array.from(
      { length: 20 },
      (_, i) => `procesar el lote ${String(i + 1).padStart(2, '0')} del cierre diario`,
    ).join('\n');

    const url = URL.createObjectURL(new Blob([lineas], { type: 'text/plain' }));
    const enlace = document.createElement('a');
    enlace.href = url;
    enlace.download = 'ejemplo-job-forge.txt';
    enlace.click();
    URL.revokeObjectURL(url);
  }

  private done(text: string): void {
    this.busy.set(false);
    this.message.set(text);
  }
}
