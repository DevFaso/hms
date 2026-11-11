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
    service = jasmine.createSpyObj<EducationService>('EducationService', ['listResources']);
    service.listResources.and.returnValue(of(resources));
    await TestBed.configureTestingModule({
      imports: [PatientEducationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: EducationService, useValue: service },
        { provide: PatientService, useValue: { search: () => of([]), lookup: () => of([]) } },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles: ['ROLE_NURSE'] }),
        },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['error']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PatientEducationComponent);
  });

  function metrics(): string[] {
    const root = fixture.nativeElement as HTMLElement;
    return Array.from(root.querySelectorAll('.care-metric strong'), (el) => el.textContent!.trim());
  }

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
