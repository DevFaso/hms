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
          JOB_TITLE: { MIDWIFE: 'Sage-femme' },
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

  it('translates a care-team job title', () => {
    // buildCareTeam sends JobTitle.name(). The panel rendered it raw, so a
    // French midwife read MIDWIFE, and the enum gate could not see it either:
    // the value was never piped, so no domain covered it.
    fixture.componentRef.setInput(
      'snapshot',
      snapshot({ careTeam: [{ role: 'MIDWIFE', name: 'Awa Sawadogo' }] }),
    );
    fixture.componentInstance.teamOpen.set(true);
    fixture.detectChanges();

    expect(textOf('.team-role')).toEqual(['Sage-femme']);
  });

  it('falls back to — when the staff member has no job title', () => {
    // The service used to send the word "Staff" here, which no pipe can
    // translate because the server had already chosen the language.
    fixture.componentRef.setInput(
      'snapshot',
      snapshot({ careTeam: [{ role: null, name: 'Awa Sawadogo' }] }),
    );
    fixture.componentInstance.teamOpen.set(true);
    fixture.detectChanges();

    expect(textOf('.team-role')).toEqual(['—']);
    expect(textOf('.team-name')).toEqual(['Awa Sawadogo']);
  });
});

/**
 * The drawer with no snapshot in it.
 *
 * `GET /me/patients/{id}/snapshot` is hospital-scoped (PR #742) and answers
 * 404 when no scope resolves. The host used to close the drawer on any
 * failure, so an authorization refusal looked exactly like a dead button —
 * and an empty drawer would be the same failure rendered as "no data", which
 * this repo has ruled out repeatedly.
 */
describe('PatientSnapshotDrawerComponent — states with no snapshot', () => {
  let fixture: ComponentFixture<PatientSnapshotDrawerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PatientSnapshotDrawerComponent, TranslateModule.forRoot()],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(PatientSnapshotDrawerComponent);
    fixture.componentRef.setInput('isOpen', true);
    fixture.componentRef.setInput('snapshot', null);
  });

  it('says the read is in flight rather than opening on nothing', () => {
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="snapshot-status"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('common.loading');
  });

  it('asks for a hospital when the scope is what is missing', () => {
    fixture.componentRef.setInput('loadError', 'NO_SCOPE');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="snapshot-no-scope"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('DASHBOARD.SNAPSHOT_NO_HOSPITAL');
    // A refusal is not a failure: no Retry, because trying again changes nothing.
    expect(fixture.nativeElement.textContent).not.toContain('COMMON.RETRY');
  });

  it('states a failure and offers a retry', () => {
    fixture.componentRef.setInput('loadError', 'FAILED');
    fixture.detectChanges();

    const error = fixture.nativeElement.querySelector('[data-testid="snapshot-error"]');
    expect(error).not.toBeNull();
    expect(error.getAttribute('role')).toBe('alert');

    let retried = 0;
    fixture.componentInstance.retryRequested.subscribe(() => retried++);
    const button = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((b) => (b.textContent ?? '').includes('COMMON.RETRY'));
    button?.click();

    expect(retried).toBe(1);
  });

  it('renders nothing at all when it is closed, whatever the error says', () => {
    fixture.componentRef.setInput('isOpen', false);
    fixture.componentRef.setInput('loadError', 'FAILED');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.snapshot-drawer')).toBeNull();
  });
});
