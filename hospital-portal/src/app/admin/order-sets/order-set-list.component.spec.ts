import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { OrderSetListComponent } from './order-set-list.component';
import { OrderSetService, OrderSetSummary } from '../../services/order-set.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

describe('OrderSetListComponent', () => {
  let fixture: ComponentFixture<OrderSetListComponent>;
  let svc: jasmine.SpyObj<OrderSetService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;

  const sample: OrderSetSummary = {
    id: 'os-1',
    name: 'Sepsis bundle',
    admissionType: 'EMERGENCY',
    hospitalId: 'h1',
    orderItems: [],
    active: true,
    version: 2,
    orderCount: 5,
    updatedAt: '2026-04-30T00:00:00Z',
  };

  const DEBOUNCE_WAIT_MS = 250;

  beforeEach(async () => {
    svc = jasmine.createSpyObj<OrderSetService>('OrderSetService', ['list', 'deactivate']);
    svc.list.and.returnValue(of({ content: [sample], totalElements: 1, number: 0, size: 20 }));
    svc.deactivate.and.returnValue(of({ ...sample, active: false }));

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
      imports: [OrderSetListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: OrderSetService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: RoleContextService, useValue: role },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderSetListComponent);
  });

  it('loads + renders the rows on init', (done) => {
    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();
      expect(svc.list).toHaveBeenCalledWith('h1', '');
      expect(fixture.nativeElement.querySelectorAll('[data-os-id="os-1"]').length).toBe(1);
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('shows the empty state when no rows', (done) => {
    svc.list.and.returnValue(of({ content: [], totalElements: 0, number: 0, size: 20 }));
    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('[data-testid="order-set-admin-empty"]'),
      ).not.toBeNull();
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('shows the error state when list fails', (done) => {
    svc.list.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('[data-testid="order-set-admin-error"]'),
      ).not.toBeNull();
      done();
    }, DEBOUNCE_WAIT_MS);
  });
});
