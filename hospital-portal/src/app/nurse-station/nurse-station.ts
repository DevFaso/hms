import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  NurseTaskService,
  NurseVitalTask,
  NurseMedicationTask,
  NurseOrderTask,
  NurseHandoff,
  NurseAnnouncement,
} from '../services/nurse-task.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-nurse-station',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './nurse-station.html',
  styleUrl: './nurse-station.scss',
})
export class NurseStationComponent implements OnInit {
  private readonly nurseService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);

  activeSection = signal<'vitals' | 'medications' | 'orders' | 'handoffs'>('vitals');
  vitals = signal<NurseVitalTask[]>([]);
  medications = signal<NurseMedicationTask[]>([]);
  orders = signal<NurseOrderTask[]>([]);
  handoffs = signal<NurseHandoff[]>([]);
  announcements = signal<NurseAnnouncement[]>([]);
  loading = signal(true);

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading.set(true);
    forkJoin({
      vitals: this.nurseService.getVitalsDue().pipe(
        catchError(() => {
          this.toast.error('Failed to load vitals');
          return of([] as NurseVitalTask[]);
        }),
      ),
      medications: this.nurseService.getMedicationMAR().pipe(
        catchError(() => {
          this.toast.error('Failed to load medications');
          return of([] as NurseMedicationTask[]);
        }),
      ),
      orders: this.nurseService.getOrders().pipe(
        catchError(() => {
          this.toast.error('Failed to load orders');
          return of([] as NurseOrderTask[]);
        }),
      ),
      handoffs: this.nurseService.getHandoffs().pipe(
        catchError(() => {
          this.toast.error('Failed to load handoffs');
          return of([] as NurseHandoff[]);
        }),
      ),
      announcements: this.nurseService
        .getAnnouncements()
        .pipe(catchError(() => of([] as NurseAnnouncement[]))),
    }).subscribe((results) => {
      this.vitals.set(results.vitals ?? []);
      this.medications.set(results.medications ?? []);
      this.orders.set(results.orders ?? []);
      this.handoffs.set(results.handoffs ?? []);
      this.announcements.set(Array.isArray(results.announcements) ? results.announcements : []);
      this.loading.set(false);
    });
  }

  setSection(s: 'vitals' | 'medications' | 'orders' | 'handoffs'): void {
    this.activeSection.set(s);
  }

  overdueCount(): number {
    return this.vitals().filter((v) => v.overdue).length;
  }
}
