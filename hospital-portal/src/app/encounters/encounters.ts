import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  EncounterService,
  EncounterRequest,
  EncounterResponse,
  EncounterType,
} from '../services/encounter.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule } from '@ngx-translate/core';

@Component({
  selector: 'app-encounters',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './encounters.html',
  styleUrl: './encounters.scss',
})
export class EncountersComponent implements OnInit {
  private readonly encounterService = inject(EncounterService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);

  /** true when the logged-in user is a super-admin (can pick any hospital) */
  isSuperAdmin = false;
  /** Non-null when the user is locked to a single hospital (all non-admin staff) */
  lockedHospitalId: string | null = null;
  /** Human-readable name of the locked hospital */
  lockedHospitalName = '';

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
  private allStaff: StaffResponse[] = [];
  departments = signal<{ id: string; name: string }[]>([]);

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

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
    'CONSULTATION',
    'FOLLOW_UP',
    'SURGERY',
    'LAB',
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

    // Determine role-based hospital access
    this.isSuperAdmin = this.auth.hasAnyRole(['ROLE_SUPER_ADMIN']);
    const permitted = this.roleContext.permittedHospitalIds;

    if (this.isSuperAdmin) {
      // Super admin: load ALL hospitals for dropdown selection
      this.hospitalService.list().subscribe({ next: (h) => this.hospitals.set(h) });
    } else {
      // All other staff: locked to their current (active) hospital
      const activeId = this.roleContext.activeHospitalId;
      const lockId = activeId || (permitted.length > 0 ? permitted[0] : null);

      if (lockId) {
        this.lockedHospitalId = lockId;
        // Use tenant-safe /me/hospital instead of /hospitals/{id} (SUPER_ADMIN-only)
        this.hospitalService.getMyHospitalAsResponse().subscribe({
          next: (h) => {
            this.hospitals.set([h]);
            this.lockedHospitalName = h.name;
            this.roleContext.activeHospitalId = lockId;
          },
        });
      } else {
        this.toast.error(
          'No hospital is associated with your account. Please contact an administrator.',
        );
      }
    }

    // ── TENANT ISOLATION: scope staff list to active hospital ──
    const hid = this.roleContext.activeHospitalId;
    this.staffService.list(hid ?? undefined).subscribe({
      next: (list) => {
        this.allStaff = list;
        this.staffMembers.set(list.map((s) => ({ id: s.id, name: s.name || s.email })));
      },
    });
    this.initPatientSearch();
  }

  // ── Patient picker ──
  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          // ── TENANT ISOLATION: always scope patient search to active hospital ──
          const hid = this.roleContext.activeHospitalId ?? undefined;
          return this.patientService.list(hid, q);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 2) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  // ── Department derivation ──
  onHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.form.departmentId = undefined;
    this.loadDepartmentsFor(hospitalId);
  }

  loadDepartmentsFor(hospitalId: string): void {
    if (!hospitalId) {
      this.departments.set([]);
      return;
    }
    const seen = new Set<string>();
    const depts = this.allStaff
      .filter((s) => s.hospitalId === hospitalId && s.departmentId)
      .reduce<{ id: string; name: string }[]>((acc, s) => {
        if (!seen.has(s.departmentId!)) {
          seen.add(s.departmentId!);
          acc.push({ id: s.departmentId!, name: s.departmentName || s.departmentId! });
        }
        return acc;
      }, []);
    this.departments.set(depts);
  }

  // ── CRUD ──
  openCreate(): void {
    this.form = this.emptyForm();
    // ── TENANT ISOLATION: pre-fill hospitalId from active context ──
    this.form.hospitalId = this.roleContext.activeHospitalId ?? '';
    this.editing.set(false);
    this.editingId = '';
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.departments.set([]);

    // Auto-lock hospital for non-super-admin staff
    if (this.lockedHospitalId) {
      this.form.hospitalId = this.lockedHospitalId;
      this.loadDepartmentsFor(this.lockedHospitalId);
    }

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
    // Pre-fill patient picker display with existing data
    this.selectedPatient.set({
      id: enc.patientId,
      firstName: enc.patientName?.split(' ')[0] ?? '',
      lastName: enc.patientName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
    } as PatientResponse);
    this.loadDepartmentsFor(enc.hospitalId ?? '');
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const payload = {
      ...this.form,
      encounterDate: this.form.encounterDate ? this.form.encounterDate + 'T00:00:00' : '',
    };
    const op = this.editing()
      ? this.encounterService.update(this.editingId, payload)
      : this.encounterService.create(payload);
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
    // ── TENANT ISOLATION: always scope encounter list to active hospital ──
    const hospitalId = this.roleContext.activeHospitalId ?? undefined;
    this.encounterService.list(hospitalId ? { hospitalId } : undefined).subscribe({
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
      list = list.filter(
        (e) =>
          e.status === 'SCHEDULED' ||
          e.status === 'ARRIVED' ||
          e.status === 'TRIAGE' ||
          e.status === 'WAITING_FOR_PHYSICIAN' ||
          e.status === 'IN_PROGRESS' ||
          e.status === 'AWAITING_RESULTS' ||
          e.status === 'READY_FOR_DISCHARGE',
      );
    } else if (tab === 'completed') {
      list = list.filter((e) => e.status === 'COMPLETED');
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
    this.encounterService
      .addNote(enc.id, { template: 'SOAP', summary: this.noteContent })
      .subscribe({
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
      case 'SCHEDULED':
        return 'status-open';
      case 'ARRIVED':
      case 'TRIAGE':
        return 'status-progress';
      case 'WAITING_FOR_PHYSICIAN':
        return 'status-warning';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'AWAITING_RESULTS':
        return 'status-warning';
      case 'READY_FOR_DISCHARGE':
        return 'status-ready';
      case 'COMPLETED':
        return 'status-completed';
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
      case 'CONSULTATION':
        return 'forum';
      case 'FOLLOW_UP':
        return 'event_repeat';
      case 'SURGERY':
        return 'medical_services';
      case 'LAB':
        return 'science';
      default:
        return 'medical_services';
    }
  }

  countByStatus(status: string): number {
    return this.encounters().filter((e) => e.status === status).length;
  }
}
