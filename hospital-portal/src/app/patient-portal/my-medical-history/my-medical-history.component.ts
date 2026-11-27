import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { forkJoin } from 'rxjs';
import {
  PatientPortalService,
  PatientDiagnosisSummary,
  SurgicalHistorySummary,
  FamilyHistorySummary,
  SocialHistorySummary,
} from '../../services/patient-portal.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { InlineSubFormDirective } from '../../shared/a11y/inline-sub-form.directive';
import { InlineDiscardAskComponent } from '../../shared/a11y/inline-discard-ask.component';
import { AuthService } from '../../auth/auth.service';
import { readPatientNote, writePatientNote } from '../../core/browser-storage';

@Component({
  selector: 'app-my-medical-history',
  standalone: true,
  imports: [
    InlineSubFormDirective,
    InlineDiscardAskComponent,
    ServerTimePipe,
    CommonModule,
    DatePipe,
    TranslateModule,
  ],
  templateUrl: './my-medical-history.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./my-medical-history.component.scss', '../patient-portal-pages.scss'],
})
export class MyMedicalHistoryComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly auth = inject(AuthService);

  medicalHistory = signal<PatientDiagnosisSummary[]>([]);
  surgicalHistory = signal<SurgicalHistorySummary[]>([]);
  familyHistory = signal<FamilyHistorySummary[]>([]);
  socialHistory = signal<SocialHistorySummary | null>(null);
  loading = signal(true);

  // Personal notes: kept in memory while the page is open, per signed-in
  // user, and cleared at sign-out (core/browser-storage.ts).
  medicalNotes = signal('');
  surgicalNotes = signal('');
  familyNotes = signal('');
  socialNotes = signal('');

  editingSection = signal<string | null>(null);

  private static readonly SECTIONS = ['medical', 'surgical', 'family', 'social'] as const;

  ngOnInit(): void {
    this.loadNotes();

    forkJoin({
      medical: this.portal.getMyMedicalHistory(),
      surgical: this.portal.getMySurgicalHistory(),
      family: this.portal.getMyFamilyHistory(),
      social: this.portal.getMySocialHistory(),
    }).subscribe({
      next: (data) => {
        this.medicalHistory.set(data.medical);
        this.surgicalHistory.set(data.surgical);
        this.familyHistory.set(data.family);
        this.socialHistory.set(data.social);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  getTobaccoStatus(): string {
    const sh = this.socialHistory();
    if (!sh) return '';
    if (sh.tobaccoQuitDate) return 'Former';
    if (sh.tobaccoUse) return 'Current';
    return 'Never';
  }

  getAlcoholStatus(): string {
    const sh = this.socialHistory();
    if (!sh) return '';
    if (sh.alcoholUse) return sh.alcoholFrequency || 'Yes';
    return '';
  }

  toggleNoteEdit(section: string): void {
    if (this.editingSection() === section) {
      this.saveNotes(section);
      this.editingSection.set(null);
    } else {
      this.editingSection.set(section);
    }
  }

  /**
   * QA D1191 / D1195 class (round 22): Escape on a personal note closes it
   * without saving (asking first over typed text) and shows the saved note
   * again, not the discarded draft.
   */
  cancelNoteEdit(section: string): void {
    if (this.editingSection() !== section) return;
    this.editingSection.set(null);
    this.onNoteChange(section, readPatientNote(this.noteOwner(), section));
  }

  /** The signed-in user the notes belong to; without one nothing is kept. */
  private noteOwner(): string | null {
    return this.auth.getUserProfile()?.id ?? this.auth.getUserId();
  }

  onNoteChange(section: string, value: string): void {
    switch (section) {
      case 'medical':
        this.medicalNotes.set(value);
        break;
      case 'surgical':
        this.surgicalNotes.set(value);
        break;
      case 'family':
        this.familyNotes.set(value);
        break;
      case 'social':
        this.socialNotes.set(value);
        break;
    }
  }

  getNotes(section: string): string {
    switch (section) {
      case 'medical':
        return this.medicalNotes();
      case 'surgical':
        return this.surgicalNotes();
      case 'family':
        return this.familyNotes();
      case 'social':
        return this.socialNotes();
      default:
        return '';
    }
  }

  private loadNotes(): void {
    const owner = this.noteOwner();
    for (const section of MyMedicalHistoryComponent.SECTIONS) {
      this.onNoteChange(section, readPatientNote(owner, section));
    }
  }

  private saveNotes(section: string): void {
    writePatientNote(this.noteOwner(), section, this.getNotes(section));
  }
}
