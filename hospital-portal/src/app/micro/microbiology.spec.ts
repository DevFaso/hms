import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { MicrobiologyComponent } from './microbiology';
import { MicroCultureResponse, MicroService } from '../services/micro.service';
import { LabService } from '../services/lab.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { RoleContextStub, roleContextStub } from '../testing/role-context.stub';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

describe('MicrobiologyComponent', () => {
  const EVERY_ROLE = [
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_PHARMACIST',
    'ROLE_RECEPTIONIST',
  ];
  let fixture: ComponentFixture<MicrobiologyComponent>;
  let component: MicrobiologyComponent;
  let microServiceSpy: jasmine.SpyObj<MicroService>;
  let labServiceSpy: jasmine.SpyObj<LabService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let scope: RoleContextStub;

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
    scope = roleContextStub({ superAdmin: false, hospitalId: 'h1', roles: EVERY_ROLE });

    await TestBed.configureTestingModule({
      imports: [MicrobiologyComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MicroService, useValue: microServiceSpy },
        { provide: LabService, useValue: labServiceSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: RoleContextService, useValue: scope },
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
    scope.set({ roles: [] });
    microServiceSpy.list.and.returnValue(of(page([culture({})])));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="new-culture"]')).toBeNull();
  });

  it('shows finalize only on preliminary reports and only to the finalize tier', () => {
    // Finalize tier excludes LAB_TECHNICIAN: mirror by refusing its role list.
    scope.set({ roles: ['ROLE_LAB_TECHNICIAN'] });
    microServiceSpy.list.and.returnValue(of(page([culture({})])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="finalize-culture"]')).toBeNull();
  });

  const finalWithAntibiogram = () =>
    culture({
      status: 'FINAL',
      growthResult: 'GROWTH',
      isolates: [
        {
          id: 'i1',
          isolateNumber: 1,
          organismName: 'E. coli',
          organismCode: null,
          growthQuantity: null,
          notes: null,
          susceptibilities: [
            {
              id: 's1',
              antibioticName: 'Ciprofloxacine',
              antibioticCode: null,
              method: null,
              micValue: null,
              interpretation: 'SUSCEPTIBLE',
              notes: null,
            },
          ],
        },
      ],
    });

  const editControls = [
    'new-culture',
    'correction-reason',
    'save-culture',
    'add-isolate',
    'add-susceptibility',
    'delete-susceptibility',
  ];

  it('QA D3902: a doctor, nurse or midwife reads a final report with no edit control at all', () => {
    scope.set({ roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'] });
    microServiceSpy.list.and.returnValue(of(page([finalWithAntibiogram()])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="culture-detail"]')).toBeTruthy();
    for (const id of editControls) {
      expect(fixture.nativeElement.querySelector(`[data-testid="${id}"]`))
        .withContext(id)
        .toBeNull();
    }
  });

  it('QA D3902: a lab technician edits a preliminary report but not a final one', () => {
    scope.set({ roles: ['ROLE_LAB_TECHNICIAN'] });
    microServiceSpy.list.and.returnValue(of(page([finalWithAntibiogram()])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="save-culture"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="correction-lab-only"]')).toBeTruthy();

    component.select(culture({ id: 'c2' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="save-culture"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="correction-lab-only"]')).toBeNull();
  });

  it('QA D3902: the scientist tier corrects a final report, and the banner names who corrected it', () => {
    scope.set({ roles: ['ROLE_LAB_SCIENTIST'] });
    microServiceSpy.list.and.returnValue(
      of(
        page([
          culture({
            status: 'CORRECTED',
            correctedAt: '2026-11-25T10:00:00',
            correctionReason: 'Re-read',
            correctedByName: 'Test Biologiste A',
          }),
        ]),
      ),
    );
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="correction-reason"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-testid="save-culture"]')).toBeTruthy();
    const banner = fixture.nativeElement.querySelector('[data-testid="correction-banner"]');
    expect(banner?.textContent).toContain('Test Biologiste A');
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

  it('QA D4176: the culture form offers only open orders of a culture test, with code and date', () => {
    const order = (over: Record<string, unknown>) =>
      ({
        id: 'o',
        labOrderCode: 'LAB-000025',
        patientFullName: 'Boukary BTest',
        labTestName: 'ECBU',
        orderDatetime: '2026-11-27T05:20:00',
        status: 'RECEIVED',
        labTestCulture: true,
        ...over,
      }) as never;
    labServiceSpy.listOrders.and.returnValue(
      of([
        order({ id: 'culture-open' }),
        order({ id: 'culture-done', status: 'COMPLETED' }),
        order({ id: 'culture-void', status: 'ENTERED_IN_ERROR' }),
        order({ id: 'glycaemia', labTestName: 'Glycémie à jeun', labTestCulture: false }),
        order({ id: 'old-server', labTestName: 'Kaliémie', labTestCulture: undefined }),
      ]),
    );
    fixture.detectChanges();
    component.openCreateModal();
    fixture.detectChanges();

    expect(component.orders().map((o) => o.id)).toEqual(['culture-open']);
    const el: HTMLElement = fixture.nativeElement;
    const options = Array.from(el.querySelectorAll('#create-order option')).slice(1);
    expect(options.length).toBe(1);
    expect(options[0].textContent).toContain('LAB-000025');
    expect(options[0].textContent).toContain('27/11/2026');
    expect(options[0].textContent).toContain('Boukary BTest — ECBU');
    expect(el.querySelector('[data-testid="create-order-hint"]')?.textContent).toContain(
      'MICRO.ORDERS_CULTURE_ONLY',
    );
  });

  it('QA D4176: with no open culture order the form says so', () => {
    labServiceSpy.listOrders.and.returnValue(of([]));
    fixture.detectChanges();
    component.openCreateModal();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="create-order-hint"]')?.textContent).toContain(
      'MICRO.ORDERS_NONE_OPEN',
    );
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

  it('QA CX-010: removing an isolate or a susceptibility asks first; the reason is not the question', async () => {
    microServiceSpy.list.and.returnValue(of(page([culture({})])));
    fixture.detectChanges();
    component.select(component.cultures()[0]);
    const ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);

    await component.deleteIsolate('i1');
    await component.deleteSusceptibility('i1', 's1');
    expect(ask).toHaveBeenCalledTimes(2);
    expect(microServiceSpy.deleteIsolate).not.toHaveBeenCalled();
    expect(microServiceSpy.deleteSusceptibility).not.toHaveBeenCalled();

    ask.and.resolveTo(true);
    microServiceSpy.deleteIsolate.and.returnValue(of(culture({})));
    microServiceSpy.deleteSusceptibility.and.returnValue(of(culture({})));
    await component.deleteIsolate('i1');
    await component.deleteSusceptibility('i1', 's1');
    expect(microServiceSpy.deleteIsolate).toHaveBeenCalledTimes(1);
    expect(microServiceSpy.deleteSusceptibility).toHaveBeenCalledTimes(1);
  });

  describe('QA CX-009: correcting a validated report to no growth', () => {
    const isolate = {
      id: 'i1',
      isolateNumber: 1,
      organismName: 'Escherichia coli',
      susceptibilities: [],
    } as unknown as MicroCultureResponse['isolates'][number];
    const validated = () =>
      culture({ status: 'FINAL', growthResult: 'GROWTH', isolates: [isolate] });

    const open = (report: MicroCultureResponse) => {
      microServiceSpy.list.and.returnValue(of(page([report])));
      fixture.detectChanges();
      component.select(report);
      component.correctionReason.set('Contamination');
      component.editGrowthResult.set('NO_GROWTH');
    };

    it('asks first, says what leaves, and only then sends the removal', () => {
      open(validated());
      component.saveCulture();
      expect(microServiceSpy.update).not.toHaveBeenCalled();
      expect(component.confirmingNoGrowth()).toBeTrue();

      microServiceSpy.update.and.returnValue(
        of(culture({ status: 'CORRECTED', growthResult: 'NO_GROWTH', isolates: [] })),
      );
      component.confirmNoGrowth();
      expect(microServiceSpy.update.calls.mostRecent().args[1]).toEqual(
        jasmine.objectContaining({ growthResult: 'NO_GROWTH', removeIsolates: true }),
      );
      expect(component.confirmingNoGrowth()).toBeFalse();
    });

    it('no organism on it: nothing to confirm, no removal asked', () => {
      open(culture({ status: 'FINAL', growthResult: 'GROWTH', isolates: [] }));
      microServiceSpy.update.and.returnValue(of(culture({ status: 'CORRECTED' })));
      component.saveCulture();
      expect(component.confirmingNoGrowth()).toBeFalse();
      expect(microServiceSpy.update.calls.mostRecent().args[1].removeIsolates).toBeUndefined();
    });

    it('a preliminary report saves as it always did: the server refuses, its reason is shown', () => {
      open(culture({ isolates: [isolate] }));
      microServiceSpy.update.and.returnValue(
        throwError(() => ({ error: { message: 'Retirez les isolats' } })),
      );
      component.saveCulture();
      expect(component.confirmingNoGrowth()).toBeFalse();
      expect(microServiceSpy.update.calls.mostRecent().args[1].removeIsolates).toBeUndefined();
      expect(toastSpy.error).toHaveBeenCalledWith('Retirez les isolats');
    });

    it('a refused last-isolate removal shows the server reason through the existing path', async () => {
      open(validated());
      component.correctionReason.set('Erreur');
      spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
      microServiceSpy.deleteIsolate.and.returnValue(
        throwError(() => ({ error: { message: 'Seul germe' } })),
      );
      await component.deleteIsolate('i1');
      expect(toastSpy.error).toHaveBeenCalledWith('Seul germe');
    });

    it('after an organism turned the report to growth, the next save does not offer to remove it', () => {
      open(culture({ status: 'FINAL', growthResult: 'NO_GROWTH', isolates: [] }));
      component.editGrowthResult.set('NO_GROWTH');
      microServiceSpy.addIsolate.and.returnValue(
        of(culture({ status: 'CORRECTED', growthResult: 'GROWTH', isolates: [isolate] })),
      );
      component.isoOrganism.set('Escherichia coli');
      component.submitIsolate();
      expect(component.editGrowthResult()).toBe('GROWTH');

      component.correctionReason.set('Note');
      microServiceSpy.update.and.returnValue(of(culture({ status: 'CORRECTED' })));
      component.saveCulture();
      expect(component.confirmingNoGrowth()).toBeFalse();
      expect(microServiceSpy.update.calls.mostRecent().args[1].growthResult).toBe('GROWTH');
    });

    it('adding an organism keeps what the lab typed and has not saved', () => {
      const preliminary = culture({ isolates: [] });
      microServiceSpy.list.and.returnValue(of(page([preliminary])));
      fixture.detectChanges();
      component.select(preliminary);
      component.editGrowthResult.set('GROWTH');
      component.editNotes.set('Pas encore enregistre');
      microServiceSpy.addIsolate.and.returnValue(of(culture({ isolates: [isolate] })));
      component.isoOrganism.set('Escherichia coli');
      component.submitIsolate();
      expect(component.editGrowthResult()).toBe('GROWTH');
      expect(component.editNotes()).toBe('Pas encore enregistre');
    });

    it('shows what the report said before each correction', () => {
      const corrected = culture({
        status: 'CORRECTED',
        growthResult: 'NO_GROWTH',
        isolates: [],
        corrections: [
          {
            id: 'k1',
            correctedAt: '2026-11-06T10:00:00',
            correctedByName: null,
            reason: 'Contamination',
            statusBefore: 'FINAL',
            growthBefore: 'GROWTH',
            gramStainBefore: null,
            notesBefore: null,
            isolatesBefore: '#1 Escherichia coli',
          },
        ],
      });
      open(corrected);
      fixture.detectChanges();
      const history = fixture.nativeElement.querySelector('[data-testid="correction-history"]');
      expect(history).toBeTruthy();
      expect(history.textContent).toContain('Escherichia coli');
    });
  });
});
