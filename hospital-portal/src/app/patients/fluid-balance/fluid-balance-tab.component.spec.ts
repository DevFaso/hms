import { TestBed, ComponentFixture } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
import { FluidBalanceTabComponent } from './fluid-balance-tab.component';
import {
  FluidBalanceService,
  IntakeOutputEntry,
  IntakeOutputSummary,
} from '../../services/fluid-balance.service';
import { ToastService } from '../../core/toast.service';

describe('FluidBalanceTabComponent', () => {
  let fixture: ComponentFixture<FluidBalanceTabComponent>;
  let component: FluidBalanceTabComponent;
  let fluidServiceSpy: jasmine.SpyObj<FluidBalanceService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  const entry = (overrides: Partial<IntakeOutputEntry>): IntakeOutputEntry => ({
    id: 'e1',
    observationTime: '2026-08-22T08:00:00',
    documentedAt: '2026-08-22T08:05:00',
    lateEntry: false,
    category: 'INTAKE',
    route: 'ORAL',
    volumeMl: 250,
    notes: null,
    recordedByName: 'Nurse A',
    ...overrides,
  });

  const summary = (overrides: Partial<IntakeOutputSummary>): IntakeOutputSummary => ({
    patientId: 'p1',
    windowFrom: '2026-08-21T08:00:00',
    windowTo: '2026-08-22T08:00:00',
    totalIntakeMl: 0,
    totalOutputMl: 0,
    balanceMl: 0,
    entries: [],
    ...overrides,
  });

  beforeEach(async () => {
    fluidServiceSpy = jasmine.createSpyObj('FluidBalanceService', ['record', 'getSummary']);
    fluidServiceSpy.getSummary.and.returnValue(of(summary({})));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [FluidBalanceTabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: FluidBalanceService, useValue: fluidServiceSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(FluidBalanceTabComponent);
    component = fixture.componentInstance;
    component.patientId = 'p1';
  });

  it('renders the server-computed totals without re-summing client-side', () => {
    fluidServiceSpy.getSummary.and.returnValue(
      of(
        summary({
          totalIntakeMl: 1500,
          totalOutputMl: 800,
          balanceMl: 700,
          entries: [entry({}), entry({ id: 'e2', category: 'OUTPUT', route: 'URINE' })],
        }),
      ),
    );
    fixture.detectChanges();

    const el = fixture.nativeElement;
    expect(el.querySelector('[data-testid="total-intake"]').textContent).toContain('1500 ml');
    expect(el.querySelector('[data-testid="total-output"]').textContent).toContain('800 ml');
    expect(el.querySelector('[data-testid="balance"]').textContent).toContain('+700 ml');
    expect(el.querySelectorAll('[data-testid="fluid-table"] tbody tr').length).toBe(2);
  });

  it('switching the window refetches with a new from bound', () => {
    fixture.detectChanges();
    expect(fluidServiceSpy.getSummary).toHaveBeenCalledTimes(1);

    fixture.nativeElement.querySelector('[data-testid="window-7d"]').click();
    fixture.detectChanges();

    expect(fluidServiceSpy.getSummary).toHaveBeenCalledTimes(2);
    const secondCall = fluidServiceSpy.getSummary.calls.mostRecent().args[1];
    expect(secondCall?.from).toBeTruthy();
  });

  it('records an entry sending only the route — never a category', () => {
    fixture.detectChanges();
    fluidServiceSpy.record.and.returnValue(of(entry({})));

    component.openModal();
    component.formRoute.set('URINE');
    component.formVolume.set(300);
    component.submit();

    expect(fluidServiceSpy.record).toHaveBeenCalledWith(
      'p1',
      jasmine.objectContaining({ route: 'URINE', volumeMl: 300 }),
    );
    const sent = fluidServiceSpy.record.calls.mostRecent().args[1];
    expect('category' in sent).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
    // Successful save refetches the summary.
    expect(fluidServiceSpy.getSummary).toHaveBeenCalledTimes(2);
  });

  it('refuses to submit without a route or a positive volume', () => {
    fixture.detectChanges();
    component.openModal();
    component.formVolume.set(250);
    component.submit();
    expect(fluidServiceSpy.record).not.toHaveBeenCalled();

    component.formRoute.set('ORAL');
    component.formVolume.set(0);
    component.submit();
    expect(fluidServiceSpy.record).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledTimes(2);
  });

  it('guards against double submission while the first call is in flight', () => {
    fixture.detectChanges();
    const pending = new Subject<IntakeOutputEntry>();
    fluidServiceSpy.record.and.returnValue(pending.asObservable());

    component.openModal();
    component.formRoute.set('IV');
    component.formVolume.set(500);
    component.submit();
    component.submit();

    expect(fluidServiceSpy.record).toHaveBeenCalledTimes(1);
  });

  it('surfaces the backend refusal message verbatim on save failure', () => {
    fixture.detectChanges();
    fluidServiceSpy.record.and.returnValue(
      throwError(() => ({ error: { message: 'Patient is not registered at this hospital.' } })),
    );

    component.openModal();
    component.formRoute.set('ORAL');
    component.formVolume.set(100);
    component.submit();

    expect(toastSpy.error).toHaveBeenCalledWith('Patient is not registered at this hospital.');
    expect(component.showModal()).toBeTrue();
  });

  it('shows the empty state when the window has no entries', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="fluid-empty"]')).toBeTruthy();
  });

  it('surfaces a load failure with a retry', () => {
    fluidServiceSpy.getSummary.and.returnValue(
      throwError(() => ({ error: { message: 'Patient not found with ID: p1' } })),
    );
    fixture.detectChanges();

    const errorBox = fixture.nativeElement.querySelector('[data-testid="fluid-error"]');
    expect(errorBox).toBeTruthy();
    expect(errorBox.textContent).toContain('Patient not found with ID: p1');
  });
});
