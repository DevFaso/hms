import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DoctorWorklistItem } from '../../services/dashboard.service';

type WorklistTab = 'ALL' | 'WAITING' | 'IN_PROGRESS' | 'CONSULTS' | 'COMPLETED';

@Component({
  selector: 'app-doctor-worklist',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './doctor-worklist.html',
  styleUrl: './doctor-worklist.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DoctorWorklistComponent {
  items = input<DoctorWorklistItem[]>([]);
  patientSelected = output<string>();

  activeTab = signal<WorklistTab>('ALL');

  readonly tabs: { key: WorklistTab; label: string }[] = [
    { key: 'ALL', label: 'All' },
    { key: 'WAITING', label: 'Waiting' },
    { key: 'IN_PROGRESS', label: 'In Progress' },
    { key: 'CONSULTS', label: 'Consults' },
    { key: 'COMPLETED', label: 'Completed' },
  ];

  filteredItems = computed(() => {
    const tab = this.activeTab();
    const all = this.items();
    if (tab === 'ALL') return all;
    if (tab === 'WAITING')
      return all.filter(
        (i) => i.encounterStatus === 'ARRIVED' || i.encounterStatus === 'SCHEDULED',
      );
    if (tab === 'IN_PROGRESS') return all.filter((i) => i.encounterStatus === 'IN_PROGRESS');
    if (tab === 'CONSULTS') return all.filter((i) => i.encounterStatus === 'CONSULTATION');
    if (tab === 'COMPLETED') return all.filter((i) => i.encounterStatus === 'COMPLETED');
    return all;
  });

  tabCounts = computed(() => {
    const all = this.items();
    return {
      ALL: all.length,
      WAITING: all.filter(
        (i) => i.encounterStatus === 'ARRIVED' || i.encounterStatus === 'SCHEDULED',
      ).length,
      IN_PROGRESS: all.filter((i) => i.encounterStatus === 'IN_PROGRESS').length,
      CONSULTS: all.filter((i) => i.encounterStatus === 'CONSULTATION').length,
      COMPLETED: all.filter((i) => i.encounterStatus === 'COMPLETED').length,
    };
  });

  setTab(tab: WorklistTab): void {
    this.activeTab.set(tab);
  }

  selectPatient(patientId: string): void {
    this.patientSelected.emit(patientId);
  }

  getUrgencyClass(urgency: string): string {
    const map: Record<string, string> = {
      EMERGENT: 'urgency-emergent',
      URGENT: 'urgency-urgent',
      ROUTINE: 'urgency-routine',
      LOW: 'urgency-low',
    };
    return map[urgency] ?? 'urgency-routine';
  }

  getStatusLabel(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'Scheduled',
      ARRIVED: 'Waiting',
      IN_PROGRESS: 'In Progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
      CONSULTATION: 'Consult',
    };
    return map[status] ?? status;
  }

  getStatusClass(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'status-scheduled',
      ARRIVED: 'status-waiting',
      IN_PROGRESS: 'status-active',
      COMPLETED: 'status-completed',
      CANCELLED: 'status-cancelled',
      CONSULTATION: 'status-consult',
    };
    return map[status] ?? '';
  }

  getInitials(name: string): string {
    const parts = (name ?? '').trim().split(' ');
    if (parts.length >= 2) return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
    return (parts[0]?.[0] ?? '?').toUpperCase();
  }

  getAvatarColor(name: string): string {
    const colors = ['#2563eb', '#059669', '#7c3aed', '#d97706', '#dc2626', '#0891b2', '#db2777'];
    let hash = 0;
    for (let i = 0; i < (name?.length ?? 0); i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return colors[Math.abs(hash) % colors.length];
  }
}
