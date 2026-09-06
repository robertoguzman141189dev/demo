import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PanelService } from './panel.service';

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

  ngOnInit(): void {
    this.panel.connect();
    this.panel.refreshDeadLetters();
  }

  protected depth(queue: string): number {
    return this.panel.queues()[queue] ?? 0;
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
