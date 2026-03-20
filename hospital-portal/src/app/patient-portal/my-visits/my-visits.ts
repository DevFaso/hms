import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import {
  PatientPortalService,
  PortalEncounter,
  PortalEncounterNote,
  PortalDischargeInstructions,
} from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-visits',
  standalone: true,
  imports: [CommonModule, DatePipe, TitleCasePipe],
  templateUrl: './my-visits.html',
  styleUrl: './my-visits.scss',
})
export class MyVisitsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  encounters = signal<PortalEncounter[]>([]);
  loading = signal(true);

  expandedId = signal<string | null>(null);
  noteMap = signal<Record<string, PortalEncounterNote | null>>({});
  instructionsMap = signal<Record<string, PortalDischargeInstructions | null>>({});
  noteLoading = signal<Record<string, boolean>>({});
  instructionsLoading = signal<Record<string, boolean>>({});

  ngOnInit() {
    this.portal.getMyEncounters().subscribe({
      next: (enc) => {
        this.encounters.set(enc);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggle(enc: PortalEncounter): void {
    const id = enc.id;
    if (this.expandedId() === id) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(id);
    if (!(id in this.noteMap())) {
      this.loadNote(id);
    }
    if (!(id in this.instructionsMap())) {
      this.loadInstructions(id);
    }
  }

  private loadNote(encounterId: string): void {
    this.noteLoading.update((m) => ({ ...m, [encounterId]: true }));
    this.portal.getMyEncounterNote(encounterId).subscribe({
      next: (note) => {
        this.noteMap.update((m) => ({ ...m, [encounterId]: note }));
        this.noteLoading.update((m) => ({ ...m, [encounterId]: false }));
      },
      error: () => {
        this.noteMap.update((m) => ({ ...m, [encounterId]: null }));
        this.noteLoading.update((m) => ({ ...m, [encounterId]: false }));
      },
    });
  }

  private loadInstructions(encounterId: string): void {
    this.instructionsLoading.update((m) => ({ ...m, [encounterId]: true }));
    this.portal.getMyPostVisitInstructions(encounterId).subscribe({
      next: (ins) => {
        this.instructionsMap.update((m) => ({ ...m, [encounterId]: ins }));
        this.instructionsLoading.update((m) => ({ ...m, [encounterId]: false }));
      },
      error: () => {
        this.instructionsMap.update((m) => ({ ...m, [encounterId]: null }));
        this.instructionsLoading.update((m) => ({ ...m, [encounterId]: false }));
      },
    });
  }
}
