import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { HospitalService } from '../services/hospital.service';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject } from 'rxjs';

import { PatientEducationComponent } from './patient-education';
import { EducationResource, EducationService } from '../services/education.service';
import { PatientService } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { roleContextStub } from '../testing/role-context.stub';

describe('PatientEducationComponent dashboard metrics', () => {
  let fixture: ComponentFixture<PatientEducationComponent>;
  let service: jasmine.SpyObj<EducationService>;

  const resources: EducationResource[] = [
    { id: 'cdx-1', title: 'Cdx Nutrition', resourceType: 'ARTICLE', category: 'NUTRITION' },
    { id: 'cdx-2', title: 'Cdx Meals', resourceType: 'ARTICLE', category: 'NUTRITION' },
    { id: 'cdx-3', title: 'Cdx Exercise', resourceType: 'VIDEO', category: 'EXERCISE' },
  ];

  beforeEach(async () => {
    service = jasmine.createSpyObj<EducationService>('EducationService', [
      'listResources',
      'createResource',
      'deleteResource',
      'trackProgress',
      'updateProgress',
    ]);
    service.listResources.and.returnValue(of(resources));
    await TestBed.configureTestingModule({
      imports: [PatientEducationComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: HospitalService,
          useValue: {
            getById: () => of({ id: 'h2', name: 'Selected hospital' }),
            list: () => of([]),
          },
        },
        { provide: EducationService, useValue: service },
        { provide: PatientService, useValue: { search: () => of([]), lookup: () => of([]) } },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles: ['ROLE_NURSE'] }),
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PatientEducationComponent);
  });

  function metrics(): string[] {
    const root = fixture.nativeElement as HTMLElement;
    return Array.from(root.querySelectorAll('.care-metric strong'), (el) => el.textContent!.trim());
  }

  for (const operation of ['create', 'delete', 'assign', 'progress'] as const) {
    for (const saved of [true, false]) {
      it(`reports ${operation} ${saved ? 'success' : 'failure'} after leaving its hospital without touching a new operation`, () => {
        const scope = TestBed.inject(RoleContextService) as ReturnType<typeof roleContextStub>;
        scope.set({ superAdmin: true, roles: ['ROLE_SUPER_ADMIN'] });
        fixture.detectChanges();
        scope.scopeToHospital('h1');
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const pending = new Subject<never>();
        if (operation === 'create') {
          service.createResource.and.returnValue(pending);
          c.openCreateResource();
          c.resourceForm.title = 'Resource';
          c.submitResource();
        } else if (operation === 'delete') {
          service.deleteResource.and.returnValue(pending);
          c.confirmDelete(resources[0]);
          c.submitDelete();
        } else if (operation === 'assign') {
          service.trackProgress.and.returnValue(pending);
          c.progressPatient.set({ id: 'p1' } as never);
          c.assignResourceId = resources[0].id;
          c.submitAssign();
        } else {
          service.updateProgress.and.returnValue(pending);
          c.openProgressUpdate({ id: 'progress-1', patientId: 'p1', resourceId: resources[0].id });
          c.submitProgressUpdate();
        }
        scope.scopeToHospital('h2');
        fixture.detectChanges();
        expect(c.resourceSaving()).toBeFalse();
        expect(c.deleteBusy()).toBeFalse();
        expect(c.assignBusy()).toBeFalse();
        expect(c.progressSaving()).toBeFalse();
        // Returning to h1 must not make a response from the old visit current.
        scope.scopeToHospital('h1');
        fixture.detectChanges();
        c.openCreateResource();
        c.resourceSaving.set(true);
        c.deleteBusy.set(true);
        c.assignBusy.set(true);
        c.progressSaving.set(true);
        if (saved) pending.next({ ...resources[0], id: 'old-result' } as never);
        else pending.error(new Error('old request failed'));
        const toast = TestBed.inject(ToastService) as jasmine.SpyObj<ToastService>;
        expect(saved ? toast.success : toast.error).toHaveBeenCalledWith(
          saved ? 'HOSPITAL_SCOPE.PREVIOUS_WRITE_SAVED' : 'HOSPITAL_SCOPE.PREVIOUS_WRITE_FAILED',
        );
        expect(c.resources()).toEqual(resources);
        expect(c.progressEntries()).toEqual([]);
        expect(c.showResourceModal()).toBeTrue();
        expect(c.resourceSaving()).toBeTrue();
        expect(c.deleteBusy()).toBeTrue();
        expect(c.assignBusy()).toBeTrue();
        expect(c.progressSaving()).toBeTrue();
      });
    }
  }

  it('requires a selected hospital for resource creation even for a super-admin', () => {
    const scope = TestBed.inject(RoleContextService) as ReturnType<typeof roleContextStub>;
    scope.set({ superAdmin: true, roles: ['ROLE_SUPER_ADMIN'] });
    scope.enableGlobalView();
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.openCreateResource();
    c.resourceForm.title = 'Resource';
    c.submitResource();
    expect(c.showResourceModal()).toBeFalse();
    expect(service.createResource).not.toHaveBeenCalled();
    scope.scopeToHospital('h2');
    fixture.detectChanges();
    c.openCreateResource();
    expect(c.showResourceModal()).toBeTrue();
  });

  it('clears progress and drops late library results when a super-admin changes hospitals', () => {
    const scope = TestBed.inject(RoleContextService) as ReturnType<typeof roleContextStub>;
    scope.set({ superAdmin: true, roles: ['ROLE_SUPER_ADMIN'] });
    const old = new Subject<EducationResource[]>();
    service.listResources.and.returnValue(old);
    fixture.detectChanges();
    service.listResources.and.returnValue(of([]));
    scope.scopeToHospital('h2');
    fixture.detectChanges();
    old.next(resources);
    fixture.detectChanges();
    expect(fixture.componentInstance.resources()).toEqual([]);
    expect(fixture.componentInstance.progressPatient()).toBeNull();
  });

  it('counts distinct loaded categories independently of visible resource filters', () => {
    fixture.detectChanges();
    expect(metrics()).toEqual(['3', '2', '3']);
    fixture.componentInstance.categoryFilter.set('NUTRITION');
    fixture.detectChanges();
    expect(metrics()).toEqual(['3', '2', '2']);
    expect(service.listResources).toHaveBeenCalledTimes(1);
  });

  it('uses placeholders during loading and failure and renders zero only after a successful empty load', () => {
    const response = new Subject<EducationResource[]>();
    service.listResources.and.returnValue(response);
    fixture.detectChanges();
    expect(metrics()).toEqual(['—', '—', '—']);
    response.error(new Error('fixture outage'));
    fixture.detectChanges();
    expect(metrics()).toEqual(['—', '—', '—']);
    service.listResources.and.returnValue(of([]));
    fixture.componentInstance.loadResources();
    fixture.detectChanges();
    expect(metrics()).toEqual(['0', '0', '0']);
  });
});
