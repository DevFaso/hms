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
  // NOTE: Replace with user/session-derived secret from a secure source in production.
  private readonly NOTES_KEY_MATERIAL = 'portal-notes-key-material';

  ngOnInit(): void {
    void this.loadNotes();

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
      void this.saveNotes(section);
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

  private async loadNotes(): Promise<void> {
    const medical = localStorage.getItem(this.NOTES_PREFIX + 'medical') || '';
    const surgical = localStorage.getItem(this.NOTES_PREFIX + 'surgical') || '';
    const family = localStorage.getItem(this.NOTES_PREFIX + 'family') || '';
    const social = localStorage.getItem(this.NOTES_PREFIX + 'social') || '';
    this.medicalNotes.set(await this.decryptNote(medical));
    this.surgicalNotes.set(await this.decryptNote(surgical));
    this.familyNotes.set(await this.decryptNote(family));
    this.socialNotes.set(await this.decryptNote(social));
  }

  private async saveNotes(section: string): Promise<void> {
    const value = this.getNotes(section);
    if (value) {
      const encrypted = await this.encryptNote(value);
      localStorage.setItem(this.NOTES_PREFIX + section, encrypted);
    } else {
      localStorage.removeItem(this.NOTES_PREFIX + section);
    }
  }

  private async getCryptoKey(salt: Uint8Array): Promise<CryptoKey> {
    const keyMaterial = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(this.NOTES_KEY_MATERIAL) as BufferSource,
      'PBKDF2',
      false,
      ['deriveKey'] as KeyUsage[],
    );
    return crypto.subtle.deriveKey(
      { name: 'PBKDF2', salt: salt as BufferSource, iterations: 100000, hash: 'SHA-256' },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt'] as KeyUsage[],
    );
  }

  private toBase64(bytes: Uint8Array): string {
    let binary = '';
    for (const byte of bytes) {
      binary += String.fromCharCode(byte);
    }
    return btoa(binary);
  }

  private fromBase64(base64: string): Uint8Array {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  private async encryptNote(plainText: string): Promise<string> {
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await this.getCryptoKey(salt);
    const cipherBuffer = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv as BufferSource },
      key,
      new TextEncoder().encode(plainText) as BufferSource,
    );
    return JSON.stringify({
      s: this.toBase64(salt),
      i: this.toBase64(iv),
      c: this.toBase64(new Uint8Array(cipherBuffer)),
    });
  }

  private async decryptNote(payload: string): Promise<string> {
    if (!payload) return '';
    try {
      const parsed = JSON.parse(payload) as { s: string; i: string; c: string };
      if (!parsed?.s || !parsed?.i || !parsed?.c) return '';
      const salt = this.fromBase64(parsed.s);
      const iv = this.fromBase64(parsed.i);
      const cipherBytes = this.fromBase64(parsed.c);
      const key = await this.getCryptoKey(salt);
      const plainBuffer = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: iv as BufferSource },
        key,
        cipherBytes as BufferSource,
      );
      return new TextDecoder().decode(plainBuffer);
    } catch {
      return '';
    }
  }
}
