import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyMedicationsComponent } from './my-medications.component';
import { PatientPortalService } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

describe('MyMedicationsComponent', () => {
  let component: MyMedicationsComponent;
  let fixture: ComponentFixture<MyMedicationsComponent>;

  const mockPortalService = {
    getMyMedications: () => of([]),
    getMyPrescriptions: () => of([]),
    getMyRefills: () => of([]),
    requestRefill: () => of({}),
  };

  const mockToast = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientPortalService, useValue: mockPortalService },
        { provide: ToastService, useValue: mockToast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MyMedicationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no medications or prescriptions', () => {
    expect(component.medications().length).toBe(0);
    expect(component.prescriptions().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle medication expand', () => {
    component.toggleMed('m1');
    expect(component.expandedMedId()).toBe('m1');
    component.toggleMed('m1');
    expect(component.expandedMedId()).toBeNull();
  });

  it('should toggle prescription expand', () => {
    component.toggleRx('r1');
    expect(component.expandedRxId()).toBe('r1');
    component.toggleRx('r1');
    expect(component.expandedRxId()).toBeNull();
  });
});
