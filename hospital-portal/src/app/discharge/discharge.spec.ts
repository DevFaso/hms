import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { DischargeComponent } from './discharge';
import { EncounterResponse } from '../services/encounter.service';

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
});
