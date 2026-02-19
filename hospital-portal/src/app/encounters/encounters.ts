import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  EncounterService,
  EncounterRequest,
  EncounterResponse,
  EncounterType,
} from '../services/encounter.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService } from '../services/staff.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-encounters',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './encounters.html',
  styleUrl: './encounters.scss',
})
export class EncountersComponent implements OnInit {
  private readonly encounterService = inject(EncounterService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);

  encounters = signal<EncounterResponse[]>([]);
  filtered = signal<EncounterResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'open' | 'completed'>('all');
  selectedEncounter = signal<EncounterResponse | null>(null);
  noteContent = '';
  showNoteForm = signal(false);

  // Dropdowns
  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<{ id: string; name: string }[]>([]);

  // CRUD
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editingId = '';
  form: EncounterRequest = this.emptyForm();
  encounterTypes: EncounterType[] = [
    'OUTPATIENT',
    'INPATIENT',
    'EMERGENCY',
    'TELEMEDICINE',
    'HOME_VISIT',
  ];

  // Delete
  showDeleteConfirm = signal(false);
  deletingEnc = signal<EncounterResponse | null>(null);
  deleting = signal(false);

  emptyForm(): EncounterRequest {
    return {
      patientId: '',
      staffId: '',
      hospitalId: '',
      encounterType: 'OUTPATIENT',
      encounterDate: '',
    };
  }

  ngOnInit(): void {
    this.loadEncounters();
    this.hospitalService.list().subscribe({ next: (h) => this.hospitals.set(h) });
    this.staffService.list().subscribe({
      next: (list) =>
        this.staffMembers.set(list.map((s) => ({ id: s.id, name: s.name || s.email }))),
    });
  }

  // ── CRUD ──
  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId = '';
    this.showModal.set(true);
  }

  openEdit(enc: EncounterResponse): void {
    this.editing.set(true);
    this.editingId = enc.id;
    this.form = {
      patientId: enc.patientId,
      staffId: enc.staffId,
      hospitalId: enc.hospitalId,
      departmentId: enc.departmentId || undefined,
      encounterType: enc.encounterType,
      encounterDate: enc.encounterDate?.split('T')[0] || '',
      notes: enc.notes || undefined,
    };
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.encounterService.update(this.editingId, this.form)
      : this.encounterService.create(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Encounter updated' : 'Encounter created');
        this.closeModal();
        this.loadEncounters();
        this.saving.set(false);
      },
      error: () => {
        this.toast.error('Failed to save encounter');
        this.saving.set(false);
      },
    });
  }

  confirmDelete(enc: EncounterResponse): void {
    this.deletingEnc.set(enc);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingEnc.set(null);
  }
  executeDelete(): void {
    const enc = this.deletingEnc();
    if (!enc) return;
    this.deleting.set(true);
    this.encounterService.delete(enc.id).subscribe({
      next: () => {
        this.toast.success('Encounter deleted');
        this.cancelDelete();
        this.loadEncounters();
        this.deleting.set(false);
      },
      error: () => {
        this.toast.error('Failed to delete encounter');
        this.deleting.set(false);
      },
    });
  }

  loadEncounters(): void {
    this.loading.set(true);
    this.encounterService.list().subscribe({
      next: (data) => {
        const list = Array.isArray(data) ? data : [];
        this.encounters.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load encounters');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'open' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.encounters();
    const tab = this.activeTab();
    if (tab === 'open') {
      list = list.filter((e) => e.status === 'OPEN' || e.status === 'IN_PROGRESS');
    } else if (tab === 'completed') {
      list = list.filter((e) => e.status === 'COMPLETED' || e.status === 'DISCHARGED');
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (e) =>
          (e.patientName ?? '').toLowerCase().includes(term) ||
          (e.staffName ?? '').toLowerCase().includes(term) ||
          (e.departmentName ?? '').toLowerCase().includes(term) ||
          (e.notes ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  selectEncounter(enc: EncounterResponse): void {
    this.selectedEncounter.set(enc);
    this.showNoteForm.set(false);
    this.noteContent = '';
  }

  closeDetail(): void {
    this.selectedEncounter.set(null);
  }

  addNote(): void {
    const enc = this.selectedEncounter();
    if (!enc || !this.noteContent.trim()) return;
    this.encounterService.addNote(enc.id, { content: this.noteContent }).subscribe({
      next: () => {
        this.toast.success('Note added successfully');
        this.noteContent = '';
        this.showNoteForm.set(false);
      },
      error: () => this.toast.error('Failed to add note'),
    });
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'OPEN':
        return 'status-open';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'COMPLETED':
        return 'status-completed';
      case 'DISCHARGED':
        return 'status-discharged';
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'OUTPATIENT':
        return 'directions_walk';
      case 'INPATIENT':
        return 'hotel';
      case 'EMERGENCY':
        return 'emergency';
      case 'TELEMEDICINE':
        return 'videocam';
      case 'HOME_VISIT':
        return 'home';
      default:
        return 'medical_services';
    }
  }

  countByStatus(status: string): number {
    return this.encounters().filter((e) => e.status === status).length;
  }
}
