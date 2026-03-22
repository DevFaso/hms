import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import { PatientPortalService, PortalHealthReminder } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-reminders',
  standalone: true,
  imports: [CommonModule, DatePipe, TitleCasePipe],
  templateUrl: './my-reminders.html',
  styleUrl: './my-reminders.scss',
})
export class MyRemindersComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  reminders = signal<PortalHealthReminder[]>([]);
  loading = signal(true);
  completing = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.portal.getMyHealthReminders().subscribe({
      next: (r) => {
        this.reminders.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  complete(reminder: PortalHealthReminder): void {
    if (this.completing()) return;
    this.completing.set(reminder.id);
    this.portal.completeHealthReminder(reminder.id).subscribe({
      next: (updated) => {
        this.reminders.update((list) => list.map((r) => (r.id === updated.id ? updated : r)));
        this.completing.set(null);
      },
      error: () => this.completing.set(null),
    });
  }

  isDone(r: PortalHealthReminder): boolean {
    return r.status === 'COMPLETED' || r.status === 'DISMISSED';
  }
}
