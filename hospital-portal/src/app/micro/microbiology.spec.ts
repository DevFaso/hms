import { TestBed, ComponentFixture } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MicrobiologyComponent } from './microbiology';
import { MicroCultureResponse, MicroService } from '../services/micro.service';
import { LabService } from '../services/lab.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

describe('MicrobiologyComponent', () => {
  let fixture: ComponentFixture<MicrobiologyComponent>;
  let component: MicrobiologyComponent;
  let microServiceSpy: jasmine.SpyObj<MicroService>;
  let labServiceSpy: jasmine.SpyObj<LabService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let roleContextSpy: jasmine.SpyObj<RoleContextService>;

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
    specimenSource: 'Blood',
    collectedAt: null,
    status: 'PRELIMINARY',
    growthResult: null,
    gramStain: null,
    finalizedAt: null,
    finalizedByName: null,
    correctedAt: null,
    correctionReason: null,
    reportedByName: null,
    notes: null,
    createdAt: '2026-08-22T10:00:00',
    updatedAt: null,
    isolates: [],
    ...overrides,
  });

  const page = (content: MicroCultureResponse[]) => ({
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
  });

  beforeEach(async () => {
    microServiceSpy = jasmine.createSpyObj('MicroService', [
      'list',
      'create',
      'update',
      'finalize',
      'addIsolate',
      'deleteIsolate',
      'addSusceptibility',
      'deleteSusceptibility',
    ]);
    microServiceSpy.list.and.returnValue(of(page([])));
    labServiceSpy = jasmine.createSpyObj('LabService', ['listOrders']);
    labServiceSpy.listOrders.and.returnValue(of([]));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info']);
    roleContextSpy = jasmine.createSpyObj('RoleContextService', ['hasAnyActiveRole']);
    roleContextSpy.hasAnyActiveRole.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [MicrobiologyComponent, TranslateModule.forRoot()],
      providers: [
        { provide: MicroService, useValue: microServiceSpy },
        { provide: LabService, useValue: labServiceSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: RoleContextService, useValue: roleContextSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MicrobiologyComponent);
    component = fixture.componentInstance;
  });

  it('lists cultures and selects one into the detail panel', () => {
    microServiceSpy.list.and.returnValue(of(page([culture({}), culture({ id: 'c2' })])));
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="cultures-table"] tbody tr');
    expect(rows.length).toBe(2);

    rows[0].click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="culture-detail"]')).toBeTruthy();
  });

  it('hides the resulting controls without a backend-authorized role', () => {
    roleContextSpy.hasAnyActiveRole.and.returnValue(false);
    microServiceSpy.list.and.returnValue(of(page([culture({})])));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="new-culture"]')).toBeNull();
  });

  it('shows finalize only on preliminary reports and only to the finalize tier', () => {
    // Finalize tier excludes LAB_TECHNICIAN: mirror by refusing its role list.
    roleContextSpy.hasAnyActiveRole.and.callFake((roles: string[]) =>
      roles.includes('ROLE_LAB_TECHNICIAN'),
    );
    microServiceSpy.list.and.returnValue(of(page([culture({})])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="finalize-culture"]')).toBeNull();
  });

  it('refuses a locked-report mutation client-side until a correction reason is given', () => {
    microServiceSpy.list.and.returnValue(of(page([culture({ status: 'FINAL' })])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);

    component.saveCulture();

    expect(toastSpy.error).toHaveBeenCalled();
    expect(microServiceSpy.update).not.toHaveBeenCalled();

    component.correctionReason.set('Stain re-read');
    microServiceSpy.update.and.returnValue(of(culture({ status: 'CORRECTED' })));
    component.saveCulture();

    expect(microServiceSpy.update).toHaveBeenCalledWith(
      'c1',
      jasmine.objectContaining({ correctionReason: 'Stain re-read' }),
    );
  });

  it('creating a culture requires an order and guards double submit', () => {
    fixture.detectChanges();
    component.openCreateModal();

    component.submitCreate();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(microServiceSpy.create).not.toHaveBeenCalled();

    component.formOrderId.set('o1');
    microServiceSpy.create.and.returnValue(of(culture({})));
    component.submitCreate();
    expect(microServiceSpy.create).toHaveBeenCalledTimes(1);
  });

  it('adds a susceptibility row with the chosen interpretation', () => {
    microServiceSpy.list.and.returnValue(
      of(
        page([
          culture({
            growthResult: 'GROWTH',
            isolates: [
              {
                id: 'i1',
                isolateNumber: 1,
                organismName: 'E. coli',
                organismCode: null,
                growthQuantity: null,
                notes: null,
                susceptibilities: [],
              },
            ],
          }),
        ]),
      ),
    );
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    component.openSuscModal('i1');
    component.suscAntibiotic.set('Ciprofloxacin');
    component.suscInterpretation.set('SUSCEPTIBLE');
    microServiceSpy.addSusceptibility.and.returnValue(of(culture({})));

    component.submitSusc();

    expect(microServiceSpy.addSusceptibility).toHaveBeenCalledWith(
      'c1',
      'i1',
      jasmine.objectContaining({
        antibioticName: 'Ciprofloxacin',
        interpretation: 'SUSCEPTIBLE',
      }),
    );
  });

  it('surfaces backend refusals verbatim on finalize', () => {
    microServiceSpy.list.and.returnValue(of(page([culture({})])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    microServiceSpy.finalize.and.returnValue(
      throwError(() => ({
        error: { message: 'A growth result is required before the report can be finalized.' },
      })),
    );

    component.finalize();

    expect(toastSpy.error).toHaveBeenCalledWith(
      'A growth result is required before the report can be finalized.',
    );
  });
});
