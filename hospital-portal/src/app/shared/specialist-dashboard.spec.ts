import { Type, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';
import { ConsultationsComponent } from '../consultations/consultations';
import { TreatmentPlansComponent } from '../treatment-plans/treatment-plans';
import { ReferralsComponent } from '../referrals/referrals';
import { ConsultationStats, ConsultationService } from '../services/consultation.service';
import { TreatmentPlanService } from '../services/treatment-plan.service';
import { ReferralService } from '../services/referral.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { roleContextStub } from '../testing/role-context.stub';

interface Dashboard {
  load(): void;
  loading: WritableSignal<boolean>;
  loadFailed: WritableSignal<boolean>;
  filtered: WritableSignal<unknown[]>;
}

for (const entry of [
  { component: ConsultationsComponent, service: ConsultationService },
  { component: TreatmentPlansComponent, service: TreatmentPlanService },
  { component: ReferralsComponent, service: ReferralService },
]) {
  describe(`${entry.component.name} dashboard reads`, () => {
    let component: Dashboard;
    let reads: jasmine.Spy;
    let toast: jasmine.SpyObj<ToastService>;

    beforeEach(() => {
      reads = jasmine.createSpy('getAll');
      toast = jasmine.createSpyObj('ToastService', ['error', 'success', 'info']);
      TestBed.configureTestingModule({
        imports: [entry.component, TranslateModule.forRoot()],
        providers: [
          provideRouter([]),
          provideHttpClient(withXhr()),
          provideHttpClientTesting(),
          { provide: entry.service, useValue: { getAll: reads, getStats: () => of(null) } },
          {
            provide: RoleContextService,
            useValue: roleContextStub({
              superAdmin: true,
              hospitalId: null,
              roles: ['ROLE_SUPER_ADMIN'],
            }),
          },
          { provide: ToastService, useValue: toast },
        ],
      });
      component = TestBed.createComponent(entry.component as Type<Dashboard>).componentInstance;
    });

    it('keeps newer scope results when a previous read completes or fails late', () => {
      const old = new Subject<unknown[]>(),
        current = new Subject<unknown[]>();
      reads.and.returnValues(old, current);
      component.load();
      component.load();
      const latest = [{ id: 'cdx-current', patientName: 'Cdx Current', status: 'REQUESTED' }];
      current.next(latest);
      old.next([{ id: 'cdx-old', patientName: 'Cdx Old', status: 'REQUESTED' }]);
      old.error(new Error('old scope unavailable'));
      expect(component.filtered()).toEqual(latest);
      expect(component.loadFailed()).toBeFalse();
      expect(component.loading()).toBeFalse();
      expect(toast.error).not.toHaveBeenCalled();
    });

    it('distinguishes current failure from empty success and recovers on retry', () => {
      const failed = new Subject<unknown[]>(),
        retry = new Subject<unknown[]>();
      reads.and.returnValues(failed, retry);
      component.load();
      failed.error(new Error('unavailable'));
      expect(component.loadFailed()).toBeTrue();
      expect(component.loading()).toBeFalse();
      component.load();
      expect(component.loadFailed()).toBeFalse();
      expect(component.loading()).toBeTrue();
      retry.next([]);
      expect(component.loadFailed()).toBeFalse();
      expect(component.loading()).toBeFalse();
      expect(component.filtered()).toEqual([]);
    });
    if (entry.component === ConsultationsComponent) {
      it('ignores an old scope summary after the new summary has arrived', () => {
        const old = new Subject<ConsultationStats>(),
          current = new Subject<ConsultationStats>();
        spyOn(TestBed.inject(ConsultationService), 'getStats').and.returnValues(old, current);
        const page = component as ConsultationsComponent;
        page.loadStats();
        page.loadStats();
        const summary = { total: 9, requested: 2, active: 3, completed: 4 } as ConsultationStats;
        current.next(summary);
        old.error(new Error('previous scope failed'));
        expect(page.stats()).toEqual(summary);
        expect(page.statsFailed()).toBeFalse();
        expect(page.statsLoading()).toBeFalse();
      });
    }
  });
}
