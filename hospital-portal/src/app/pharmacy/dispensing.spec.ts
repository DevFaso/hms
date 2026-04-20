import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { DispensingComponent } from './dispensing';
import { PharmacyService } from '../services/pharmacy.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { of, throwError } from 'rxjs';

describe('DispensingComponent', () => {
  let component: DispensingComponent;
  let fixture: ComponentFixture<DispensingComponent>;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;
  let authSvc: jasmine.SpyObj<AuthService>;
  let toastSvc: jasmine.SpyObj<ToastService>;

  const mockPharmacies = {
    content: [{ id: 'ph-1', name: 'Main Pharmacy' }],
    totalElements: 1,
    totalPages: 1,
    size: 100,
    number: 0,
  };

  const mockWorkQueue = {
    data: {
      content: [
        {
          id: 'rx-1',
          medicationName: 'Amoxicillin',
          dosage: '500mg',
          quantity: 30,
          status: 'SIGNED',
          patient: { id: 'pat-1', firstName: 'John', lastName: 'Doe' },
          staff: { user: { firstName: 'Dr.', lastName: 'Smith' } },
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
    },
  };

  const mockDispenses = {
    data: {
      content: [
        {
          id: 'd-1',
          medicationName: 'Amoxicillin',
          quantityDispensed: 30,
          unit: 'tablets',
          status: 'COMPLETED',
          dispensedByName: 'Pharmacist A',
          dispensedAt: '2025-06-01T10:00:00',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 10,
      number: 0,
    },
  };

  const mockInventory = {
    data: {
      content: [{ id: 'inv-1', medicationName: 'Amoxicillin', quantityOnHand: 100 }],
      totalElements: 1,
      totalPages: 1,
      size: 200,
      number: 0,
    },
  };

  beforeEach(async () => {
    pharmacySvc = jasmine.createSpyObj('PharmacyService', [
      'listPharmacies',
      'getDispenseWorkQueue',
      'listDispensesByPharmacy',
      'listInventoryByPharmacy',
      'createDispense',
      'cancelDispense',
    ]);
    authSvc = jasmine.createSpyObj('AuthService', [], {
      currentProfile: () => ({ id: 'user-1' }),
    });
    toastSvc = jasmine.createSpyObj('ToastService', ['success', 'error']);

    pharmacySvc.listPharmacies.and.returnValue(of(mockPharmacies as any));
    pharmacySvc.getDispenseWorkQueue.and.returnValue(of(mockWorkQueue as any));
    pharmacySvc.listDispensesByPharmacy.and.returnValue(of(mockDispenses as any));
    pharmacySvc.listInventoryByPharmacy.and.returnValue(of(mockInventory as any));

    await TestBed.configureTestingModule({
      imports: [DispensingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        { provide: AuthService, useValue: authSvc },
        { provide: ToastService, useValue: toastSvc },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DispensingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load pharmacies on init', () => {
    expect(pharmacySvc.listPharmacies).toHaveBeenCalled();
    expect(component.pharmacies().length).toBe(1);
  });

  it('should load work queue after pharmacies', () => {
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalled();
    expect(component.workQueue().length).toBe(1);
  });

  it('should open dispense form when prescription selected', () => {
    const rx = mockWorkQueue.data.content[0];
    component.selectPrescription(rx);

    expect(component.showForm()).toBeTrue();
    expect(component.form.prescriptionId).toBe('rx-1');
    expect(component.form.patientId).toBe('pat-1');
    expect(component.form.medicationName).toBe('Amoxicillin');
  });

  it('should close form', () => {
    component.showForm.set(true);
    component.closeForm();

    expect(component.showForm()).toBeFalse();
    expect(component.selectedPrescription).toBeNull();
  });

  it('should dispense medication', () => {
    const mockResponse = { data: { id: 'd-new', status: 'COMPLETED' } };
    pharmacySvc.createDispense.and.returnValue(of(mockResponse as any));

    component.form = {
      prescriptionId: 'rx-1',
      patientId: 'pat-1',
      pharmacyId: 'ph-1',
      dispensedBy: 'user-1',
      medicationName: 'Amoxicillin',
      quantityRequested: 30,
      quantityDispensed: 30,
    };
    component.submitDispense();

    expect(pharmacySvc.createDispense).toHaveBeenCalledWith(component.form);
    expect(toastSvc.success).toHaveBeenCalledWith('Medication dispensed successfully');
    expect(component.showForm()).toBeFalse();
  });

  it('should show error on dispense failure', () => {
    pharmacySvc.createDispense.and.returnValue(
      throwError(() => ({ error: { message: 'Insufficient stock' } })),
    );

    component.form = {
      prescriptionId: 'rx-1',
      patientId: 'pat-1',
      pharmacyId: 'ph-1',
      dispensedBy: 'user-1',
      medicationName: 'Amoxicillin',
      quantityRequested: 30,
      quantityDispensed: 30,
    };
    component.submitDispense();

    expect(toastSvc.error).toHaveBeenCalledWith('Insufficient stock');
  });

  it('should cancel dispense', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    const mockResponse = { data: { id: 'd-1', status: 'CANCELLED' } };
    pharmacySvc.cancelDispense.and.returnValue(of(mockResponse as any));

    component.cancelDispense('d-1');

    expect(pharmacySvc.cancelDispense).toHaveBeenCalledWith('d-1');
    expect(toastSvc.success).toHaveBeenCalledWith('Dispense cancelled');
  });

  it('should return correct badge class for status', () => {
    expect(component.getStatusClass('COMPLETED')).toBe('badge-success');
    expect(component.getStatusClass('PARTIAL')).toBe('badge-warning');
    expect(component.getStatusClass('CANCELLED')).toBe('badge-danger');
    expect(component.getStatusClass('PENDING')).toBe('badge-info');
  });

  it('should handle pagination', () => {
    component.queueTotalPages = 3;
    component.queuePage = 0;

    component.nextPage();
    expect(component.queuePage).toBe(1);

    component.prevPage();
    expect(component.queuePage).toBe(0);

    component.prevPage();
    expect(component.queuePage).toBe(0); // Should not go below 0
  });
});
