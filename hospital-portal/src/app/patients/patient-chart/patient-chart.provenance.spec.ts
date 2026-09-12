import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PatientChartComponent } from './patient-chart.component';
import { PatientService, TimelineEntry, PatientTimeline } from '../../services/patient.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { AuthService } from '../../auth/auth.service';

/**
 * E8 #50 — what the chart tells a clinician about where a row came from.
 *
 * The backend has stamped `sourceHospitalName`, `clinician` and `foreign` on
 * every timeline row since #582, but the portal's `TimelineEntry` interface
 * declared no `metadata` field, so all of it arrived on the wire and was
 * discarded. These tests pin the contract: hospital, clinician and origin are
 * readable on the row itself.
 */
describe('PatientChartComponent — timeline provenance', () => {
  let fixture: ComponentFixture<PatientChartComponent>;
  let component: PatientChartComponent;

  function entry(overrides: Partial<TimelineEntry> = {}): TimelineEntry {
    return {
      entryId: 'e-1',
      category: 'ENCOUNTER',
      occurredAt: '2026-03-12T09:00:00Z',
      summary: 'Consultation générale',
      sensitive: false,
      ...overrides,
    };
  }

  function setup(entries: TimelineEntry[]) {
    const timeline: PatientTimeline = {
      patientId: 'p-1',
      patientName: 'Awa Traoré',
      accessReason: 'Suivi clinique',
      entries,
      totalEntries: entries.length,
      generatedAt: '2026-03-12T10:00:00Z',
    };

    const patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'getDoctorTimeline',
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
    ]);
    patientService.getDoctorTimeline.and.returnValue(of(timeline));
    patientService.listAllergies.and.returnValue(of([]));
    patientService.listDiagnoses.and.returnValue(of([]));
    patientService.listChartUpdates.and.returnValue(of({ content: [], totalElements: 0 }));

    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => ['ROLE_DOCTOR'],
            getHospitalId: () => 'h-1',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });

    fixture = TestBed.createComponent(PatientChartComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
  }

  it('reads the source hospital off a row', () => {
    setup([]);

    const row = entry({ metadata: { sourceHospitalName: 'Hôpital Saint-Camille' } });

    expect(component.entryHospital(row)).toBe('Hôpital Saint-Camille');
  });

  it('reads the treating clinician off a row', () => {
    setup([]);

    const row = entry({ metadata: { clinician: 'Dr. Awa Traoré' } });

    expect(component.entryClinician(row)).toBe('Dr. Awa Traoré');
  });

  it('marks a row from another hospital as foreign', () => {
    setup([]);

    expect(component.isForeignEntry(entry({ metadata: { foreign: true } }))).toBeTrue();
    expect(component.isForeignEntry(entry({ metadata: { foreign: false } }))).toBeFalse();
  });

  it('treats a row with no metadata as local rather than throwing', () => {
    setup([]);

    // Pre-#582 rows and any category that never stamped provenance still
    // render; they must not blow up the whole timeline.
    const bare = entry();

    expect(component.entryHospital(bare)).toBeNull();
    expect(component.entryClinician(bare)).toBeNull();
    expect(component.isForeignEntry(bare)).toBeFalse();
  });

  it('does not infer foreignness from the hospital name alone', () => {
    setup([]);

    // A local row carries its hospital name too — that is deliberate, so that
    // "no badge" cannot be confused with "provenance missing". Only the
    // explicit flag makes a row foreign.
    const local = entry({
      metadata: { sourceHospitalName: 'CHU de Ouagadougou', foreign: false },
    });

    expect(component.entryHospital(local)).toBe('CHU de Ouagadougou');
    expect(component.isForeignEntry(local)).toBeFalse();
  });

  // E9 #61 — the same provenance on the allergies, problems and updates rows.
  it('marks a row recorded at another hospital as foreign, by id', () => {
    setup([]);
    expect(component.isForeignRow({ hospitalId: 'h-2' })).toBeTrue();
  });

  it('treats a row from the active hospital as local', () => {
    setup([]);
    expect(component.isForeignRow({ hospitalId: 'h-1' })).toBeFalse();
  });

  it('treats a row with no hospital id as local rather than foreign', () => {
    setup([]);
    expect(component.isForeignRow({})).toBeFalse();
  });
});
