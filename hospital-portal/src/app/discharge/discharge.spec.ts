import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
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
  // QA D1004 (round 18): the discharge-request dialog left focus on its opener.
  it('D1004: moves focus into the discharge-request dialog and gives it back on close', () => {
    const fixture = TestBed.createComponent(DischargeComponent);
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    fixture.componentInstance.showRequestModal.set(true);
    fixture.detectChanges();
    const dialog = (fixture.nativeElement as HTMLElement).querySelector(
      '[role="dialog"]',
    ) as HTMLElement;
    expect(dialog).not.toBeNull();
    expect(dialog.contains(document.activeElement)).withContext('focus in the dialog').toBeTrue();
    fixture.componentInstance.closeRequest();
    fixture.detectChanges();
    expect(document.activeElement).withContext('focus back on the opener').toBe(opener);
    opener.remove();
  });

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
    // Finalize stays the discharging provider's (the server's rule): the
    // treating doctor may edit a nurse-held draft, and finalizes it once her
    // edit has made her its provider.
    expect(
      component.canFinalizeSummary({ ...other, editableByCaller: true } as DischargeSummary),
    ).toBeFalse();
    expect(
      component.canFinalizeSummary({ ...own, editableByCaller: true } as DischargeSummary),
    ).toBeTrue();
  });

  // QA D1065: a discharge was requested (200 PENDING) for a walk-in never admitted.
  describe('QA D1065: the request is for a patient with an open stay', () => {
    function pick(id: string): void {
      component.onRequestPatientPicked({ id, firstName: 'Odile', lastName: 'Yaméogo' } as never);
    }

    it('a never-admitted patient (204, no admission) cannot be sent', () => {
      const http = TestBed.inject(HttpTestingController);
      pick('p-walkin');
      expect(component.requestStay()).toBe('checking');
      expect(component.canSubmitRequest()).toBeFalse();
      http
        .expectOne((r) => r.url.endsWith('/admissions/patient/p-walkin/current'))
        .flush(null, {
          status: 204,
          statusText: 'No Content',
        });
      expect(component.requestStay()).toBe('not-admitted');
      expect(component.canSubmitRequest()).toBeFalse();
      spyOn(component as never, 'requireStaffContext').and.returnValue(true as never);
      component.submitRequest();
      expect(component.requestSubmitting()).toBeFalse();
      http.verify();
    });

    it('an admitted patient can be sent', () => {
      const http = TestBed.inject(HttpTestingController);
      pick('p-ward');
      http
        .expectOne((r) => r.url.endsWith('/admissions/patient/p-ward/current'))
        .flush({ id: 'adm-1', patientId: 'p-ward', status: 'ACTIVE' });
      expect(component.requestStay()).toBe('admitted');
      expect(component.canSubmitRequest()).toBeTrue();
    });

    it('a failed check leaves the decision to the server, and a late answer for another pick is dropped', () => {
      const http = TestBed.inject(HttpTestingController);
      pick('p-1');
      const first = http.expectOne((r) => r.url.endsWith('/admissions/patient/p-1/current'));
      pick('p-2');
      first.flush({ id: 'adm-1', patientId: 'p-1', status: 'ACTIVE' });
      expect(component.requestStay()).toBe('checking');
      http
        .expectOne((r) => r.url.endsWith('/admissions/patient/p-2/current'))
        .flush('boom', { status: 500, statusText: 'Server Error' });
      expect(component.requestStay()).toBe('unknown');
      expect(component.canSubmitRequest()).toBeTrue();
    });
  });
});
