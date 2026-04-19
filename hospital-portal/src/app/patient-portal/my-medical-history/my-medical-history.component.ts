import { Component, OnInit, inject, signal } from '@angular/core';
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

@Component({
  selector: 'app-my-medical-history',
  standalone: true,
  imports: [CommonModule, DatePipe, TranslateModule],
  templateUrl: './my-medical-history.component.html',
  styleUrls: ['./my-medical-history.component.scss', '../patient-portal-pages.scss'],
})
export class MyMedicalHistoryComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  medicalHistory = signal<PatientDiagnosisSummary[]>([]);
  surgicalHistory = signal<SurgicalHistorySummary[]>([]);
  familyHistory = signal<FamilyHistorySummary[]>([]);
  socialHistory = signal<SocialHistorySummary | null>(null);
  loading = signal(true);

  // Personal notes (localStorage-backed)
  medicalNotes = signal('');
  surgicalNotes = signal('');
  familyNotes = signal('');
  socialNotes = signal('');

  editingSection = signal<string | null>(null);

  private readonly NOTES_PREFIX = 'portal-notes-';

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
    this.medicalNotes.set(localStorage.getItem(this.NOTES_PREFIX + 'medical') || '');
    this.surgicalNotes.set(localStorage.getItem(this.NOTES_PREFIX + 'surgical') || '');
    this.familyNotes.set(localStorage.getItem(this.NOTES_PREFIX + 'family') || '');
    this.socialNotes.set(localStorage.getItem(this.NOTES_PREFIX + 'social') || '');
  }

  private saveNotes(section: string): void {
    const value = this.getNotes(section);
    if (value) {
      localStorage.setItem(this.NOTES_PREFIX + section, value);
    } else {
      localStorage.removeItem(this.NOTES_PREFIX + section);
    }
  }
}
