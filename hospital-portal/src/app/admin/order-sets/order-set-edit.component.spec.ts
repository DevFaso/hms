import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { OrderSetEditComponent } from './order-set-edit.component';
import { OrderSetService, OrderSetSummary } from '../../services/order-set.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

describe('OrderSetEditComponent', () => {
  let fixture: ComponentFixture<OrderSetEditComponent>;
  let svc: jasmine.SpyObj<OrderSetService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;

  const existing: OrderSetSummary = {
    id: 'os-9',
    name: 'Sepsis bundle',
    description: 'Hour-1 sepsis',
    admissionType: 'EMERGENCY',
    hospitalId: 'h1',
    orderItems: [{ orderType: 'LAB', orderName: 'Lactate' }],
    clinicalGuidelines: 'Surviving Sepsis Campaign 2021',
    active: true,
    version: 1,
    orderCount: 1,
  };

  async function configure(idParam: string): Promise<void> {
    svc = jasmine.createSpyObj<OrderSetService>('OrderSetService', [
      'getById',
      'versions',
      'create',
      'update',
    ]);
    svc.getById.and.returnValue(of(existing));
    svc.versions.and.returnValue(of([existing]));
    svc.create.and.returnValue(of(existing));
    svc.update.and.returnValue(of({ ...existing, version: 2 }));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getUserProfile']);
    auth.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'admin',
      email: 'admin@example.org',
      roles: ['ROLE_HOSPITAL_ADMIN'],
      staffId: 'staff-1',
      hospitalIds: ['h1'],
      active: true,
    });
    const role = jasmine.createSpyObj<RoleContextService>('RoleContextService', [], {
      activeHospitalId: 'h1',
    });

    await TestBed.configureTestingModule({
      imports: [OrderSetEditComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: OrderSetService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: RoleContextService, useValue: role },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: (k: string) => (k === 'id' ? idParam : null) } },
          },
        },
      ],
    }).compileComponents();
  }

  it('loads the existing template into the form when id != "new"', async () => {
    await configure('os-9');
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    expect(svc.getById).toHaveBeenCalledOnceWith('os-9');
    expect(svc.versions).toHaveBeenCalledOnceWith('os-9');
    const formEl = fixture.nativeElement.querySelector('[data-testid="order-set-edit-form"]');
    expect(formEl).not.toBeNull();
  });

  it('uses create when id == "new" and emits a success toast on save', async () => {
    await configure('new');
    const navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    fixture.componentInstance['form'].name = 'New bundle';
    fixture.componentInstance['form'].admissionType = 'ELECTIVE';
    fixture.componentInstance['orderItemsJson'] = '[]';

    (fixture.componentInstance as unknown as { save(): void }).save();

    expect(svc.create).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/admin/order-sets']);
  });

  it('surfaces a json error when orderItems text is not valid JSON', async () => {
    await configure('new');
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    fixture.componentInstance['form'].name = 'X';
    fixture.componentInstance['orderItemsJson'] = '{ not json';
    (fixture.componentInstance as unknown as { save(): void }).save();
    fixture.detectChanges();

    expect(svc.create).not.toHaveBeenCalled();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-set-edit-json-error"]'),
    ).not.toBeNull();
  });
});
