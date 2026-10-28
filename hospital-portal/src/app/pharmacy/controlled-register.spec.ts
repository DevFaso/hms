import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ControlledRegisterComponent } from './controlled-register';
import { PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';

describe('ControlledRegisterComponent (QA D967)', () => {
  let fixture: ComponentFixture<ControlledRegisterComponent>;
  let component: ControlledRegisterComponent;
  let svc: jasmine.SpyObj<PharmacyService>;
  let toast: jasmine.SpyObj<ToastService>;

  const register = {
    pharmacyId: 'ph-1',
    pharmacyName: 'Pharmacie centrale',
    from: '2026-10-01',
    to: '2026-10-27',
    truncated: false,
    drugs: [
      {
        inventoryItemId: 'inv-mor',
        medicationName: 'Morphine 10 mg',
        unit: 'ampoule',
        openingBalance: 70,
        closingBalance: 88,
        quantityOnHand: 86,
        entries: [
          {
            transactionId: 't-1',
            occurredAt: '2026-10-27T09:00:00',
            movementType: 'DISPENSE',
            quantity: -2,
            lotNumber: 'R16-MOR-01',
            balanceAfter: 88,
            patientName: 'Awa Sanou',
            prescriberName: 'Issa Kaboré',
            recordedBy: 'Mariam Traoré',
            witnessedBy: 'Paul Zongo',
            reason: 'Délivrance sur ordonnance',
          },
        ],
      },
    ],
  };

  beforeEach(async () => {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listPharmacies',
      'listInventoryByPharmacy',
      'getControlledRegister',
      'exportControlledRegister',
    ]);
    svc.listPharmacies.and.returnValue(
      of({ content: [{ id: 'ph-1', name: 'Pharmacie centrale' }] }) as never,
    );
    svc.listInventoryByPharmacy.and.returnValue(
      of({
        data: {
          content: [
            { id: 'inv-mor', medicationName: 'Morphine 10 mg', controlled: true },
            { id: 'inv-pcm', medicationName: 'Paracétamol', controlled: false },
          ],
        },
      }) as never,
    );
    svc.getControlledRegister.and.returnValue(of({ data: register }) as never);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [ControlledRegisterComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: svc },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ControlledRegisterComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the first pharmacy’s register for the month so far', () => {
    expect(svc.getControlledRegister).toHaveBeenCalledWith(
      'ph-1',
      component.from,
      component.to,
      undefined,
    );
    expect(component.from <= component.to).toBeTrue();
  });

  it('offers only the controlled drugs in the drug filter', () => {
    expect(component.controlledItems().map((i) => i.id)).toEqual(['inv-mor']);
  });

  it('shows each movement with its balance, patient, prescriber and witness, and flags a shelf that differs', () => {
    const el: HTMLElement = fixture.nativeElement;
    const card = el.querySelector('[data-testid="register-drug-inv-mor"]')!;
    const text = card.textContent ?? '';
    expect(text).toContain('Awa Sanou');
    expect(text).toContain('Issa Kaboré');
    expect(text).toContain('Paul Zongo');
    expect(text).toContain('-2');
    expect(el.querySelector('[data-testid="register-discrepancy-inv-mor"]')).not.toBeNull();
  });

  it('filters by drug and refuses a backwards period', () => {
    component.selectedItemId = 'inv-mor';
    component.load();
    expect(svc.getControlledRegister.calls.mostRecent().args[3]).toBe('inv-mor');

    component.from = '2026-10-27';
    component.to = '2026-10-01';
    svc.getControlledRegister.calls.reset();
    component.load();
    expect(svc.getControlledRegister).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.REGISTER_PERIOD_INVALID');
  });

  it('exports the same register as CSV and PDF', () => {
    const blob = new Blob(['x']);
    svc.exportControlledRegister.and.returnValue(of(blob));
    spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
    const anchorClick = spyOn(HTMLAnchorElement.prototype, 'click');

    component.export('csv');
    component.export('pdf');

    expect(svc.exportControlledRegister.calls.argsFor(0)).toEqual([
      'csv',
      'ph-1',
      component.from,
      component.to,
      undefined,
    ]);
    expect(svc.exportControlledRegister.calls.argsFor(1)[0]).toBe('pdf');
    expect(anchorClick).toHaveBeenCalledTimes(2);
  });

  it('says so when the export fails', () => {
    svc.exportControlledRegister.and.returnValue(throwError(() => new Error('x')));
    component.export('pdf');
    expect(toast.error).toHaveBeenCalledWith('PHARMACY.REGISTER_EXPORT_FAILED');
  });
});
