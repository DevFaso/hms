import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MortalityService, VoidedDeathClosures } from '../services/mortality.service';
import { VoidedDeathClosuresBannerComponent } from './voided-death-closures-banner.component';

/** QA D1516: the chart shows what a death entered in error had closed, and nothing otherwise. */
describe('VoidedDeathClosuresBannerComponent', () => {
  let fixture: ComponentFixture<VoidedDeathClosuresBannerComponent>;
  let mortality: jasmine.SpyObj<MortalityService>;

  const voided: VoidedDeathClosures = {
    deathRecordId: 'd1',
    enteredInErrorAt: '2026-11-07T10:00:00',
    stays: [
      { admissionId: 'a1', admittedAt: '2026-11-03T08:00:00', closedAt: '2026-11-07T09:55:00' },
    ],
    visits: [{ encounterId: 'e1', visitAt: '2026-11-07T07:00:00' }],
    appointments: [{ appointmentId: 'p1', appointmentDate: '2026-11-20' }],
  };

  function render(answer: ReturnType<MortalityService['voidedClosures']>): HTMLElement {
    mortality.voidedClosures.and.returnValue(answer);
    fixture = TestBed.createComponent(VoidedDeathClosuresBannerComponent);
    fixture.componentRef.setInput('patientId', 'patient-1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    mortality = jasmine.createSpyObj<MortalityService>('MortalityService', ['voidedClosures']);
    TestBed.configureTestingModule({
      imports: [VoidedDeathClosuresBannerComponent, TranslateModule.forRoot()],
      providers: [{ provide: MortalityService, useValue: mortality }],
    });
  });

  it('names the stays, visits and appointments the voided death closed', () => {
    const el = render(of([voided]));
    expect(mortality.voidedClosures).toHaveBeenCalledWith('patient-1');
    const section = el.querySelector('[data-testid="voided-death-closures"]');
    expect(section).not.toBeNull();
    expect(section?.getAttribute('role')).toBe('status');
    expect(el.querySelectorAll('[data-testid="voided-closure-stay"]').length).toBe(1);
    expect(el.querySelectorAll('[data-testid="voided-closure-visit"]').length).toBe(1);
    expect(el.querySelectorAll('[data-testid="voided-closure-appointment"]').length).toBe(1);
    expect(el.textContent).toContain('MORTALITY.VOIDED_CLOSURES.NOT_REOPENED');
  });

  it('shows nothing when no voided death closed anything', () => {
    const el = render(of([]));
    expect(el.querySelector('[data-testid="voided-death-closures"]')).toBeNull();
  });

  it('shows nothing, and never breaks the chart, when the read fails', () => {
    const el = render(throwError(() => new Error('403')));
    expect(el.querySelector('[data-testid="voided-death-closures"]')).toBeNull();
  });
});
