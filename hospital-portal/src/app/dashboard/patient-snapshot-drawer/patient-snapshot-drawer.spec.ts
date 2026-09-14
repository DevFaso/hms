import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PatientSnapshotDrawerComponent } from './patient-snapshot-drawer';
import { PatientSnapshot } from '../../services/dashboard.service';

/**
 * These three badges used to print words the SERVER had already chosen the
 * language for: PatientSnapshotServiceImpl stamped the literals "Vitals",
 * "Lab" and "Encounter", so a French clinician read English no matter what the
 * portal did. The backend now sends tokens (VITALS / LAB / the EncounterType
 * name, or null when the encounter has no type) and the drawer translates
 * them, which is the only split where the portal can win.
 */
describe('PatientSnapshotDrawerComponent — server-stamped type badges', () => {
  let fixture: ComponentFixture<PatientSnapshotDrawerComponent>;

  function snapshot(overrides: Partial<PatientSnapshot> = {}): PatientSnapshot {
    return {
      patientId: 'p-1',
      name: 'Awa Traoré',
      age: 34,
      sex: 'F',
      mrn: 'MRN-1',
      allergies: [],
      activeDiagnoses: [],
      activeMedications: [],
      recentVitals: [{ type: 'VITALS', value: '120/80', timestamp: '2026-09-14 10:00' }],
      latestLabs: [],
      pendingOrders: [{ type: 'LAB', description: 'CBC', orderedAt: '2026-09-14 09:00' }],
      recentNotes: [
        { author: 'Dr Ouedraogo', type: 'INPATIENT', date: '2026-09-14', snippet: 'Stable.' },
      ],
      careTeam: [],
      ...overrides,
    };
  }

  function textOf(selector: string): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll(selector) as NodeListOf<HTMLElement>)
      .map((el) => (el.textContent ?? '').trim())
      .filter((t) => t.length > 0);
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PatientSnapshotDrawerComponent, TranslateModule.forRoot()],
      providers: [provideRouter([])],
    }).compileComponents();

    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          SNAPSHOT_ITEM_TYPE: { VITALS: 'Constantes', LAB: 'Laboratoire' },
          ENCOUNTER_TYPE: { INPATIENT: 'Hospitalisation' },
        },
      },
    });

    fixture = TestBed.createComponent(PatientSnapshotDrawerComponent);
    fixture.componentRef.setInput('isOpen', true);
  });

  it('translates the vital and order badges the server used to spell in English', () => {
    fixture.componentRef.setInput('snapshot', snapshot());
    fixture.detectChanges();

    expect(textOf('.vital-type')).toEqual(['Constantes']);
    expect(textOf('.order-type-badge')).toEqual(['Laboratoire']);
    // The tokens themselves must not reach the screen in any language.
    expect(textOf('.vital-type')).not.toContain('VITALS');
    expect(textOf('.order-type-badge')).not.toContain('LAB');
  });

  it('translates a note type through the encounter vocabulary', () => {
    fixture.componentRef.setInput('snapshot', snapshot());
    fixture.componentInstance.notesOpen.set(true);
    fixture.detectChanges();

    expect(textOf('.note-type')).toEqual(['Hospitalisation']);
  });

  it('falls back to — when the encounter has no type at all', () => {
    // The service sends null rather than the word "Encounter": inventing a
    // label for it collided with CONSULTATION, which is already « Consultation ».
    fixture.componentRef.setInput(
      'snapshot',
      snapshot({
        recentNotes: [
          {
            author: 'Dr Ouedraogo',
            type: null as unknown as string,
            date: '2026-09-14',
            snippet: 'Stable.',
          },
        ],
      }),
    );
    fixture.componentInstance.notesOpen.set(true);
    fixture.detectChanges();

    expect(textOf('.note-type')).toEqual(['—']);
  });
});
