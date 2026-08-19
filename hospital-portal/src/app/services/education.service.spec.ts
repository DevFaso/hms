import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { EducationService } from './education.service';

describe('EducationService', () => {
  let service: EducationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), EducationService],
    });
    service = TestBed.inject(EducationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs the resource list as a bare array', () => {
    service.listResources().subscribe((resources) => expect(resources.length).toBe(2));
    const req = httpMock.expectOne('/patient-education/resources');
    expect(req.request.method).toBe('GET');
    req.flush([
      { id: 'r1', title: 'A', resourceType: 'ARTICLE', category: 'NUTRITION' },
      { id: 'r2', title: 'B', resourceType: 'VIDEO', category: 'BREASTFEEDING' },
    ]);
  });

  it('POSTs a new resource', () => {
    service
      .createResource({
        title: 'Warning signs in pregnancy',
        resourceType: 'CHECKLIST',
        category: 'WARNING_SIGNS',
        isWarningSignContent: true,
      })
      .subscribe((resource) => expect(resource.id).toBe('r1'));
    const req = httpMock.expectOne('/patient-education/resources');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.isWarningSignContent).toBeTrue();
    req.flush({ id: 'r1', title: 'Warning signs in pregnancy' });
  });

  it('PUTs a resource update to its id', () => {
    service
      .updateResource('r1', { title: 'Updated', resourceType: 'ARTICLE', category: 'NUTRITION' })
      .subscribe();
    const req = httpMock.expectOne('/patient-education/resources/r1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.title).toBe('Updated');
    req.flush({ id: 'r1', title: 'Updated' });
  });

  it('DELETEs a resource (soft delete)', () => {
    service.deleteResource('r1').subscribe();
    const req = httpMock.expectOne('/patient-education/resources/r1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('POSTs progress with patientId as a query param, not in the body', () => {
    service
      .trackProgress('p1', { resourceId: 'r1', comprehensionStatus: 'NOT_STARTED' })
      .subscribe();
    const req = httpMock.expectOne('/patient-education/progress?patientId=p1');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ resourceId: 'r1', comprehensionStatus: 'NOT_STARTED' });
    req.flush({ id: 'pr1', patientId: 'p1', resourceId: 'r1' });
  });

  it('PUTs a progress update to the progress id', () => {
    service
      .updateProgress('pr1', { resourceId: 'r1', progressPercentage: 100, rating: 5 })
      .subscribe();
    const req = httpMock.expectOne('/patient-education/progress/pr1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.progressPercentage).toBe(100);
    req.flush({ id: 'pr1', comprehensionStatus: 'COMPLETED', progressPercentage: 100 });
  });

  it('GETs a patient progress list as a bare array', () => {
    service.progressForPatient('p1').subscribe((rows) => expect(rows.length).toBe(1));
    const req = httpMock.expectOne('/patient-education/progress/patient/p1');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'pr1', patientId: 'p1', resourceId: 'r1' }]);
  });
});
