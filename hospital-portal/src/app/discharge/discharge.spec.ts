import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DischargeComponent } from './discharge';
import { EncounterResponse } from '../services/encounter.service';
import { DischargeSummary } from '../services/discharge.service';

describe('DischargeComponent — encounter picker label', () => {
  let component: DischargeComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DischargeComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    component = TestBed.createComponent(DischargeComponent).componentInstance;
  });

  // #948 review: the label sliced the UTC string, so a 21:00 New York visit
  // on 24 October was listed as 2026-10-25. The stored time is built from
  // local Date constructors, so this holds in any workstation zone.
  it('labels an encounter with the reader day of its stored time', () => {
    const stored = new Date(2026, 9, 24, 21, 0).toISOString().slice(0, 19);
    const label = component.encounterLabel({
      id: 'e1',
      encounterDate: stored,
      encounterType: 'OUTPATIENT',
      status: 'COMPLETED',
    } as EncounterResponse);
    expect(label).toBe('2026-10-24 — OUTPATIENT (COMPLETED)');
  });

  it('labels an encounter with no date without a stray date', () => {
    const label = component.encounterLabel({
      id: 'e2',
      encounterType: 'INPATIENT',
      status: 'ARRIVED',
    } as EncounterResponse);
    expect(label).toBe('— INPATIENT (ARRIVED)');
  });

  // QA D903: reopening a summary rebuilds the picker chip from the row, so the
  // row's MRN must travel with it or the chip falls back to an empty line.
  it('keeps the summary MRN on the preselected patient when editing (QA D903)', () => {
    spyOn(component as never, 'requireStaffContext').and.returnValue(true as never);
    spyOn(component as never, 'loadEncountersFor');
    component.openEditSummary({
      id: 's1',
      patientId: 'pz',
      patientName: 'Fatimata Zongo',
      patientMrn: 'CHW5448',
      encounterId: 'e1',
      hospitalId: 'h1',
      hospitalName: 'H',
      dischargingProviderId: 'u1',
      dischargingProviderName: 'Dr',
      dischargeDate: '2026-10-26',
    } as DischargeSummary);
    expect(component.selectedPatient()?.mrn).toBe('CHW5448');
    expect(component.selectedPatient()?.firstName).toBe('Fatimata');
  });

  // QA D930: only the discharging provider may edit, finalize or delete a draft.
  it('offers the draft actions only on the signed-in doctor own summary (QA D930)', () => {
    const writable = component as unknown as {
      canEditSummaries: boolean;
      isSuperAdmin: boolean;
      staffId: string | null;
    };
    writable.canEditSummaries = true;
    writable.isSuperAdmin = false;
    writable.staffId = 'staff-a';
    const own = { id: 's1', dischargingProviderId: 'staff-a' } as DischargeSummary;
    const other = { id: 's2', dischargingProviderId: 'staff-b' } as DischargeSummary;
    expect(component.canWriteSummary(own)).toBeTrue();
    expect(component.canWriteSummary(other)).toBeFalse();
    // When the server says who may (review of #971), its answer wins: a
    // nurse-held draft the treating doctor may finish, a colleague's it may not.
    expect(
      component.canWriteSummary({ ...other, editableByCaller: true } as DischargeSummary),
    ).toBeTrue();
    expect(
      component.canWriteSummary({ ...own, editableByCaller: false } as DischargeSummary),
    ).toBeFalse();
  });
});
