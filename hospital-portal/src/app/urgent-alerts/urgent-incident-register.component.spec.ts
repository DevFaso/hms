import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import {
  UrgentIncidentRegisterComponent,
  groupCases,
  reminders,
  stepKeys,
} from './urgent-incident-register.component';
import { UrgentAlertIncident, UrgentAlertsService } from '../services/urgent-alerts.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { UrgentAlertsStub, urgentAlertsStub } from '../testing/urgent-alerts.stub';

function line(overrides: Partial<UrgentAlertIncident>): UrgentAlertIncident {
  return {
    id: 'i-1',
    caseKey: 'case-a',
    source: 'NEWS2_VITAL_SIGN',
    sourceLabel: 'Alerte précoce NEWS2 (signes vitaux)',
    patientId: null,
    patientName: null,
    raisedAt: '2026-11-27T05:20:52',
    openedAt: '2026-11-27T06:21:43',
    minutesUnacknowledged: 60,
    recipientsTold: 4,
    stepsTaken: 4,
    lastStepAt: '2026-11-27T06:51:46',
    acknowledgedByName: null,
    closedAt: null,
    resolution: null,
    ...overrides,
  };
}

describe('UrgentIncidentRegisterComponent (QA D4102)', () => {
  let fixture: ComponentFixture<UrgentIncidentRegisterComponent>;
  let alerts: UrgentAlertsStub;

  function setup(roles: string[]): void {
    TestBed.configureTestingModule({
      imports: [UrgentIncidentRegisterComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: UrgentAlertsService, useValue: alerts },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
      ],
    });
    fixture = TestBed.createComponent(UrgentIncidentRegisterComponent);
    fixture.detectChanges();
  }

  const all = (testId: string): HTMLElement[] =>
    Array.from(fixture.nativeElement.querySelectorAll(`[data-testid="${testId}"]`));

  it('shows an administrator the open incidents as cases, with no patient and no link to a chart', () => {
    alerts = urgentAlertsStub({
      incidents: [
        line({ id: 'i-1' }),
        line({
          id: 'i-2',
          source: 'NEWBORN_VITAL_SIGN',
          sourceLabel: 'Signes vitaux du nouveau-né',
        }),
        line({
          id: 'i-3',
          caseKey: 'case-b',
          closedAt: '2026-11-27T06:52:36',
          resolution: 'ACKNOWLEDGED',
          acknowledgedByName: 'Test Médecin A',
        }),
      ],
    });
    setup(['ROLE_HOSPITAL_ADMIN']);

    // Eighteen identical bell notices said nothing; the register says how many are open and groups them.
    expect(all('urgent-incidents-summary')[0].textContent).toContain('URGENT_INCIDENTS.OPEN_MANY');
    const cases = all('urgent-incident-case');
    expect(cases.length).toBe(1);
    expect(cases[0].textContent).toContain('URGENT_INCIDENTS.CASE');
    expect(cases[0].textContent).toContain('Alerte précoce NEWS2 (signes vitaux)');
    expect(cases[0].textContent).toContain('Signes vitaux du nouveau-né');
    expect(cases[0].textContent).toContain('URGENT_INCIDENTS.NOT_CLOSED');
    expect(cases[0].querySelector('a')).toBeNull();
  });

  it('shows the closed incidents too, with who acknowledged, once asked', () => {
    alerts = urgentAlertsStub({
      incidents: [
        line({
          closedAt: '2026-11-27T06:52:36',
          resolution: 'ACKNOWLEDGED',
          acknowledgedByName: 'Test Médecin A',
        }),
      ],
    });
    setup(['ROLE_HOSPITAL_ADMIN']);
    expect(all('urgent-incident-case').length).toBe(0);
    expect(all('urgent-incidents-empty').length).toBe(1);

    fixture.componentInstance.showClosed.set(true);
    fixture.detectChanges();

    expect(all('urgent-incident-case')[0].textContent).toContain(
      'URGENT_INCIDENTS.ACKNOWLEDGED_BY',
    );
    expect(all('urgent-incident-case')[0].textContent).toContain('URGENT_INCIDENTS.STATUS_CLOSED');
  });

  it('gives the medical lead the patient and a link to the chart', () => {
    alerts = urgentAlertsStub({
      incidents: [line({ patientId: 'p-1', patientName: 'Adama TestEchelle' })],
    });
    setup(['ROLE_DOCTOR']);

    const link = all('urgent-incident-case')[0].querySelector('a');
    expect(link?.textContent).toContain('Adama TestEchelle');
    expect(link?.getAttribute('href')).toBe('/patients/p-1');
  });

  it('says why the register was refused, and offers to try again', () => {
    alerts = urgentAlertsStub();
    alerts.incidents.and.returnValue(
      throwError(() => ({ status: 403, error: { message: 'Réservé au médecin-chef.' } })),
    );
    setup(['ROLE_DOCTOR']);

    expect(all('urgent-incidents-failed')[0].textContent).toContain('Réservé au médecin-chef.');

    alerts.incidents.and.returnValue(of([line({})]));
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(all('urgent-incident-case').length).toBe(1);
  });

  it('fits a 390 px screen: a case and its lines never scroll sideways', () => {
    alerts = urgentAlertsStub({
      incidents: [
        line({ patientId: 'p-1', patientName: 'R81-CLINICAL Rasmata TestTravail', stepsTaken: 6 }),
        line({ id: 'i-2', patientId: 'p-1', patientName: 'R81-CLINICAL Rasmata TestTravail' }),
      ],
    });
    setup(['ROLE_DOCTOR']);
    const host: HTMLElement = fixture.nativeElement;
    host.style.display = 'block';
    host.style.width = '390px';
    fixture.detectChanges();

    const page = all('urgent-incident-register')[0];
    expect(page.scrollWidth).toBeLessThanOrEqual(page.clientWidth + 1);
    for (const card of all('urgent-incident-case')) {
      expect(card.scrollWidth).toBeLessThanOrEqual(card.clientWidth + 1);
    }
  });

  it('reads the register again when the hospital scope moves', () => {
    alerts = urgentAlertsStub({ incidents: [line({})] });
    setup(['ROLE_HOSPITAL_ADMIN']);
    alerts.incidents.calls.reset();

    (TestBed.inject(RoleContextService) as ReturnType<typeof roleContextStub>).set({
      hospitalId: 'h-2',
    });
    fixture.detectChanges();

    expect(alerts.incidents).toHaveBeenCalledTimes(1);
  });
});

describe('the incident register helpers', () => {
  it('names the steps of the ladder and counts the reminders apart', () => {
    expect(stepKeys(null)).toEqual([]);
    expect(stepKeys(1)).toEqual(['URGENT_INCIDENTS.STEP_COVER']);
    expect(stepKeys(5)).toEqual([
      'URGENT_INCIDENTS.STEP_COVER',
      'URGENT_INCIDENTS.STEP_LEAD',
      'URGENT_INCIDENTS.STEP_INCIDENT',
    ]);
    expect(reminders(3)).toBe(0);
    expect(reminders(5)).toBe(2);
  });

  it('puts an open case first and keeps the earliest opening of a case', () => {
    const cases = groupCases([
      line({
        id: 'c',
        caseKey: 'closed',
        openedAt: '2026-11-27T07:00:00',
        closedAt: '2026-11-27T07:05:00',
      }),
      line({ id: 'a', caseKey: 'open', openedAt: '2026-11-27T06:36:00' }),
      line({ id: 'b', caseKey: 'open', openedAt: '2026-11-27T06:21:43' }),
    ]);

    expect(cases.map((c) => c.key)).toEqual(['open', 'closed']);
    expect(cases[0].open).toBe(2);
    expect(cases[0].openedAt).toBe('2026-11-27T06:21:43');
  });
});
