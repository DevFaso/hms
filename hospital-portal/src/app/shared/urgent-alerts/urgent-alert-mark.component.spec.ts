import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { UrgentAlertMarkComponent } from './urgent-alert-mark.component';
import { UrgentAlertsService } from '../../services/urgent-alerts.service';
import { urgentAlertsStub } from '../../testing/urgent-alerts.stub';

describe('UrgentAlertMarkComponent (QA D4101)', () => {
  let fixture: ComponentFixture<UrgentAlertMarkComponent>;

  function setup(patientId: string | null): void {
    TestBed.configureTestingModule({
      imports: [UrgentAlertMarkComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: UrgentAlertsService,
          useValue: urgentAlertsStub({
            marks: [{ patientId: 'p-ladder', open: 2, oldestRaisedAt: '2026-11-27T05:20:52' }],
          }),
        },
      ],
    });
    fixture = TestBed.createComponent(UrgentAlertMarkComponent);
    fixture.componentRef.setInput('patientId', patientId);
    fixture.componentRef.setInput('now', new Date('2026-11-27T06:22:30Z'));
    fixture.detectChanges();
  }

  const mark = (): HTMLElement | null =>
    fixture.nativeElement.querySelector('[data-testid="urgent-alert-mark"]');

  it('marks a patient with unacknowledged urgent alerts: how many, and the oldest age', () => {
    setup('p-ladder');

    expect(mark()).not.toBeNull();
    expect(mark()?.textContent).toContain('2');
    expect(mark()?.textContent).toContain('URGENT_ALERTS.AGE_HOURS');
    // The accessible name says what the mark is; the glyph and the number alone would not.
    expect(mark()?.getAttribute('aria-label')).toContain('URGENT_ALERTS.MARK_MANY');
    expect(fixture.componentInstance.ageText()).toBe('1 h 1 min');
  });

  it('renders nothing for a patient with no alert waiting, or with no patient', () => {
    setup('p-other');
    expect(mark()).toBeNull();
  });

  it('renders nothing with no patient', () => {
    setup(null);
    expect(mark()).toBeNull();
  });
});
