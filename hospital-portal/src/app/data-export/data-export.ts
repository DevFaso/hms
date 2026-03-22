import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DataExportService } from '../services/data-export.service';
import { ToastService } from '../core/toast.service';

interface ExportOption {
  key: string;
  label: string;
  description: string;
  icon: string;
  color: string;
}

@Component({
  selector: 'app-data-export',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './data-export.html',
  styleUrl: './data-export.scss',
})
export class DataExportComponent {
  private readonly exportService = inject(DataExportService);
  private readonly toast = inject(ToastService);

  exporting = signal<string | null>(null);

  readonly exportOptions: ExportOption[] = [
    {
      key: 'users',
      label: 'Users',
      description: 'Export all platform user accounts (username, email, status, last login)',
      icon: 'group',
      color: '#3b82f6',
    },
    {
      key: 'patients',
      label: 'Patients',
      description: 'Export all patient demographics (name, DOB, contact, blood type)',
      icon: 'personal_injury',
      color: '#10b981',
    },
  ];

  startExport(key: string): void {
    this.exporting.set(key);
    try {
      if (key === 'users') {
        this.exportService.exportUsers();
      } else if (key === 'patients') {
        this.exportService.exportPatients();
      }
      this.toast.success('Export started — file will download shortly');
    } catch {
      this.toast.error('Export failed');
    }
    // Reset after a brief delay for UX
    setTimeout(() => this.exporting.set(null), 2000);
  }
}
