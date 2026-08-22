import { TestBed, ComponentFixture } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MicroTabComponent } from './micro-tab.component';
import { MicroCultureResponse, MicroService } from '../../services/micro.service';

describe('MicroTabComponent', () => {
  let fixture: ComponentFixture<MicroTabComponent>;
  let component: MicroTabComponent;
  let microServiceSpy: jasmine.SpyObj<MicroService>;

  const culture = (overrides: Partial<MicroCultureResponse>): MicroCultureResponse => ({
    id: 'c1',
    labOrderId: 'o1',
    labOrderCode: 'o1',
    labTestName: 'Blood Culture',
    patientId: 'p1',
    patientName: 'Awa Kaboré',
    hospitalId: 'h1',
    hospitalName: 'CHU',
    specimenId: null,
    specimenAccessionNumber: null,
    specimenSource: 'Blood — peripheral',
    collectedAt: '2026-08-20T09:00:00',
    status: 'PRELIMINARY',
    growthResult: null,
    gramStain: null,
    finalizedAt: null,
    finalizedByName: null,
    correctedAt: null,
    correctionReason: null,
    reportedByName: 'Lab Sci',
    notes: null,
    createdAt: '2026-08-20T10:00:00',
    updatedAt: null,
    isolates: [],
    ...overrides,
  });

  beforeEach(async () => {
    microServiceSpy = jasmine.createSpyObj('MicroService', ['listForPatient']);
    microServiceSpy.listForPatient.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [MicroTabComponent, TranslateModule.forRoot()],
      providers: [{ provide: MicroService, useValue: microServiceSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(MicroTabComponent);
    component = fixture.componentInstance;
    component.patientId = 'p1';
  });

  it('shows the empty state when the patient has no cultures', () => {
    fixture.detectChanges();

    expect(microServiceSpy.listForPatient).toHaveBeenCalledWith('p1');
    expect(fixture.nativeElement.querySelector('[data-testid="micro-empty"]')).toBeTruthy();
  });

  it('renders a culture with its isolates and susceptibility rows', () => {
    microServiceSpy.listForPatient.and.returnValue(
      of([
        culture({
          status: 'FINAL',
          growthResult: 'GROWTH',
          isolates: [
            {
              id: 'i1',
              isolateNumber: 1,
              organismName: 'Escherichia coli',
              organismCode: null,
              growthQuantity: '>100,000 CFU/mL',
              notes: null,
              susceptibilities: [
                {
                  id: 's1',
                  antibioticName: 'Amoxicillin',
                  antibioticCode: null,
                  method: 'DISK_DIFFUSION',
                  micValue: null,
                  interpretation: 'RESISTANT',
                  notes: null,
                },
                {
                  id: 's2',
                  antibioticName: 'Ciprofloxacin',
                  antibioticCode: null,
                  method: 'MIC',
                  micValue: '<=0.25',
                  interpretation: 'SUSCEPTIBLE',
                  notes: null,
                },
              ],
            },
          ],
        }),
      ]),
    );
    fixture.detectChanges();

    const el = fixture.nativeElement;
    expect(el.querySelectorAll('[data-testid="culture-card"]').length).toBe(1);
    expect(el.querySelectorAll('[data-testid="isolate-block"]').length).toBe(1);
    expect(el.querySelectorAll('[data-testid="susc-table"] tbody tr').length).toBe(2);
    expect(el.textContent).toContain('Escherichia coli');
    expect(el.querySelector('.interp-chip.resistant')).toBeTruthy();
    expect(el.querySelector('.interp-chip.susceptible')).toBeTruthy();
  });

  it('shows the correction banner only on corrected reports', () => {
    microServiceSpy.listForPatient.and.returnValue(
      of([
        culture({
          status: 'CORRECTED',
          correctedAt: '2026-08-21T10:00:00',
          correctionReason: 'Stain re-read',
        }),
        culture({ id: 'c2' }),
      ]),
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="correction-banner"]').length).toBe(
      1,
    );
  });

  it('surfaces the backend refusal verbatim on load failure', () => {
    microServiceSpy.listForPatient.and.returnValue(
      throwError(() => ({ error: { message: 'Patient not found with ID: p1' } })),
    );
    fixture.detectChanges();

    const errorEl = fixture.nativeElement.querySelector('[data-testid="micro-error"]');
    expect(errorEl).toBeTruthy();
    expect(errorEl.textContent).toContain('Patient not found with ID: p1');
  });
});
