import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { UrgentAlertsBannerComponent } from './urgent-alerts-banner.component';
import { urgentAlertAge } from './urgent-alert-age';
import { InBasketItem, InBasketService } from '../../services/in-basket.service';
import { UrgentAlertsService } from '../../services/urgent-alerts.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { UrgentAlertsStub, urgentAlertsStub } from '../../testing/urgent-alerts.stub';

function item(id: string): InBasketItem {
  return {
    id,
    itemType: 'MESSAGE',
    priority: 'CRITICAL',
    status: 'UNREAD',
    title: 'Alerte post-partum (hypertension) : TA 160/90 mmHg',
    body: '',
    referenceId: 'alert-' + id,
    referenceType: 'OBSTETRIC_ALERT',
    encounterId: '',
    patientId: 'p-1',
    patientName: 'Rasmata TestTravail',
    orderingProviderName: '',
    createdAt: '2026-11-27T05:42:00',
    readAt: null,
    acknowledgedAt: null,
  };
}

describe('UrgentAlertsBannerComponent (QA D4100, D4101)', () => {
  let fixture: ComponentFixture<UrgentAlertsBannerComponent>;
  let alerts: UrgentAlertsStub;
  let inBasket: jasmine.SpyObj<InBasketService>;
  let toast: jasmine.SpyObj<ToastService>;

  function setup(roles: string[], mode: 'summary' | 'list' = 'summary'): void {
    inBasket = jasmine.createSpyObj<InBasketService>('InBasketService', ['acknowledge']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    TestBed.configureTestingModule({
      imports: [UrgentAlertsBannerComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: UrgentAlertsService, useValue: alerts },
        { provide: InBasketService, useValue: inBasket },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles }),
        },
      ],
    });
    fixture = TestBed.createComponent(UrgentAlertsBannerComponent);
    fixture.componentRef.setInput('mode', mode);
    fixture.detectChanges();
  }

  const query = (testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  it('shows a nurse the count of urgent alerts waiting, not nothing, and links to the in-basket', () => {
    alerts = urgentAlertsStub({
      mine: { toAcknowledge: 21, oldestRaisedAt: '2026-11-27T05:20:00', items: [] },
    });
    setup(['ROLE_NURSE']);

    expect(query('urgent-alerts-banner')).not.toBeNull();
    expect(query('urgent-alerts-count')?.textContent).toContain('URGENT_ALERTS.TO_ACK_MANY');
    expect(query('urgent-alerts-age')).not.toBeNull();
    expect(query('urgent-alerts-open')?.getAttribute('href')).toBe('/in-basket');
    expect(alerts.refreshMine).toHaveBeenCalled();
  });

  it('tells the documenter that the alerts she documented still wait, with nothing of hers to acknowledge', () => {
    alerts = urgentAlertsStub({ mine: { toAcknowledge: 0, documentedWaiting: 1 } });
    setup(['ROLE_MIDWIFE']);

    expect(query('urgent-alerts-count')).toBeNull();
    expect(query('urgent-alerts-documented')?.textContent).toContain(
      'URGENT_ALERTS.DOCUMENTED_ONE',
    );
  });

  it('uses the singular for one alert', () => {
    alerts = urgentAlertsStub({ mine: { toAcknowledge: 1 } });
    setup(['ROLE_DOCTOR']);

    expect(query('urgent-alerts-count')?.textContent).toContain('URGENT_ALERTS.TO_ACK_ONE');
  });

  it('renders nothing when nothing waits', () => {
    alerts = urgentAlertsStub();
    setup(['ROLE_DOCTOR']);

    expect(query('urgent-alerts-banner')).toBeNull();
  });

  it('asks nothing and shows nothing for a role no urgent alert is filed to', () => {
    alerts = urgentAlertsStub({ mine: { toAcknowledge: 3 } });
    setup(['ROLE_RECEPTIONIST']);

    expect(query('urgent-alerts-banner')).toBeNull();
    expect(alerts.refreshMine).not.toHaveBeenCalled();
  });

  it('lists each alert with its own acknowledge button, and reads everything again after one', () => {
    alerts = urgentAlertsStub({ mine: { toAcknowledge: 2, items: [item('a'), item('b')] } });
    setup(['ROLE_DOCTOR'], 'list');
    inBasket.acknowledge.and.returnValue(of({ ...item('a'), status: 'ACKNOWLEDGED' }));
    const done = jasmine.createSpy('acknowledged');
    fixture.componentInstance.acknowledged.subscribe(done);
    alerts.refreshMine.calls.reset();

    const buttons = fixture.nativeElement.querySelectorAll(
      '[data-testid="urgent-alert-acknowledge"]',
    );
    expect(buttons.length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('TA 160/90 mmHg');
    (buttons[0] as HTMLButtonElement).click();

    expect(inBasket.acknowledge).toHaveBeenCalledOnceWith('a');
    expect(alerts.refreshMine).toHaveBeenCalled();
    expect(alerts.refreshMarks).toHaveBeenCalled();
    expect(done).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
  });

  it('fits a 390 px screen: the text wraps and nothing scrolls sideways', () => {
    const long = {
      ...item('a'),
      title:
        'Alerte nouveau-né (oxygénation) pour Bébé TestTravail : Saturation en oxygène inférieure à 90 % à l’air ambiant. Mesuré à 05:31 : SpO₂ 89 %.',
    };
    alerts = urgentAlertsStub({
      mine: { toAcknowledge: 1, oldestRaisedAt: '2026-11-27T05:31:00', items: [long] },
    });
    setup(['ROLE_DOCTOR'], 'list');
    const host: HTMLElement = fixture.nativeElement;
    host.style.display = 'block';
    // 390 px less the page's 16 px gutters.
    host.style.width = '358px';
    fixture.detectChanges();

    const section = query('urgent-alerts-banner') as HTMLElement;
    expect(section.scrollWidth).toBeLessThanOrEqual(section.clientWidth + 1);
    const button = query('urgent-alert-acknowledge') as HTMLElement;
    expect(button.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
    expect(button.getBoundingClientRect().right).toBeLessThanOrEqual(
      host.getBoundingClientRect().right + 1,
    );
  });

  it('shows why an acknowledgement was refused', () => {
    alerts = urgentAlertsStub({ mine: { toAcknowledge: 1, items: [item('a')] } });
    setup(['ROLE_DOCTOR'], 'list');
    inBasket.acknowledge.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Déjà confirmé.' } })),
    );

    fixture.componentInstance.acknowledge(item('a'));

    expect(toast.error).toHaveBeenCalledWith('Déjà confirmé.');
    expect(fixture.componentInstance.acknowledging()).toBeNull();
  });

  it('drops the previous hospital alerts and reads again when the scope moves', () => {
    alerts = urgentAlertsStub({ mine: { toAcknowledge: 1 } });
    setup(['ROLE_DOCTOR']);
    alerts.resetMine.calls.reset();
    alerts.refreshMine.calls.reset();

    (TestBed.inject(RoleContextService) as ReturnType<typeof roleContextStub>).set({
      hospitalId: 'h-2',
    });
    fixture.detectChanges();

    expect(alerts.resetMine).toHaveBeenCalled();
    expect(alerts.refreshMine).toHaveBeenCalled();
  });
});

describe('urgentAlertAge', () => {
  const now = new Date('2026-11-27T06:22:30Z');

  it('reads the server time as UTC and gives minutes under an hour', () => {
    expect(urgentAlertAge('2026-11-27T05:36:40', now)).toEqual({
      key: 'URGENT_ALERTS.AGE_MINUTES',
      params: { minutes: 45, hours: 0 },
    });
  });

  it('gives hours and minutes from an hour on', () => {
    expect(urgentAlertAge('2026-11-27T05:20:52', now)).toEqual({
      key: 'URGENT_ALERTS.AGE_HOURS',
      params: { minutes: 1, hours: 1 },
    });
  });

  it('is never negative, and is null with no time', () => {
    expect(urgentAlertAge('2026-11-27T07:00:00', now)?.params.minutes).toBe(0);
    expect(urgentAlertAge(null, now)).toBeNull();
    expect(urgentAlertAge('not a date', now)).toBeNull();
  });
});
