import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, EducationProgressDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-education-progress',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-education-progress.html',
  styleUrl: './my-education-progress.scss',
})
export class MyEducationProgressComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  all = signal<EducationProgressDTO[]>([]);
  inProgressData = signal<EducationProgressDTO[]>([]);
  completedData = signal<EducationProgressDTO[]>([]);
  loading = signal(true);
  filter = signal<'all' | 'in_progress' | 'completed'>('all');
  inProgressLoaded = false;
  completedLoaded = false;

  inProgress = computed(() => this.inProgressData());

  completed = computed(() => this.completedData());

  visible = computed<EducationProgressDTO[]>(() => {
    if (this.filter() === 'in_progress') return this.inProgress();
    if (this.filter() === 'completed') return this.completed();
    return this.all();
  });

  ngOnInit() {
    this.portal.getMyEducationProgress().subscribe({
      next: (data) => {
        this.all.set(data);
        this.inProgressData.set(
          data.filter(
            (p) =>
              p.comprehensionStatus?.toUpperCase() === 'IN_PROGRESS' ||
              (p.progressPercentage != null &&
                p.progressPercentage > 0 &&
                p.comprehensionStatus?.toUpperCase() !== 'COMPLETED'),
          ),
        );
        this.completedData.set(
          data.filter((p) => p.comprehensionStatus?.toUpperCase() === 'COMPLETED'),
        );
        this.inProgressLoaded = true;
        this.completedLoaded = true;
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  setFilter(next: 'all' | 'in_progress' | 'completed'): void {
    this.filter.set(next);
    if (next === 'in_progress' && !this.inProgressLoaded) {
      this.loading.set(true);
      this.portal.getMyInProgressEducation().subscribe({
        next: (data) => {
          this.inProgressData.set(data);
          this.inProgressLoaded = true;
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
      return;
    }
    if (next === 'completed' && !this.completedLoaded) {
      this.loading.set(true);
      this.portal.getMyCompletedEducation().subscribe({
        next: (data) => {
          this.completedData.set(data);
          this.completedLoaded = true;
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
    }
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'completed';
    if (s === 'IN_PROGRESS') return 'in-progress';
    return '';
  }

  statusIcon(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'task_alt';
    if (s === 'IN_PROGRESS') return 'pending';
    return 'school';
  }

  comprehensionLabel(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'Completed';
    if (s === 'IN_PROGRESS') return 'In Progress';
    if (s === 'NOT_STARTED') return 'Not Started';
    return status ?? 'Unknown';
  }

  formatTime(seconds: number): string {
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
    return `${Math.floor(seconds / 3600)}h ${Math.round((seconds % 3600) / 60)}m`;
  }
}
