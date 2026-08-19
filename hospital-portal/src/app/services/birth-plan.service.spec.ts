import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { BirthPlanService } from './birth-plan.service';

describe('BirthPlanService', () => {
  let service: BirthPlanService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), BirthPlanService],
    });
    service = TestBed.inject(BirthPlanService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new plan', () => {
    service
      .create({
        patientId: 'p1',
        hospitalId: 'h1',
        introduction: { patientName: 'Jane Doe', expectedDueDate: '2026-12-01' },
        flexibilityAcknowledgment: true,
      })
      .subscribe();
    const req = httpMock.expectOne('/birth-plans');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.flexibilityAcknowledgment).toBeTrue();
    req.flush({ id: 'bp1' });
  });

  it('builds search params from the provided filters only', () => {
    service.search({ hospitalId: 'h1', providerReviewed: false, page: 1, size: 10 }).subscribe();
    const req = httpMock.expectOne(
      '/birth-plans/search?hospitalId=h1&providerReviewed=false&page=1&size=10',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 1, size: 10 });
  });

  it('GETs pending-review with optional hospital scope', () => {
    service.pendingReview('h1', 0, 20).subscribe((page) => expect(page.totalElements).toBe(2));
    const req = httpMock.expectOne('/birth-plans/pending-review?page=0&size=20&hospitalId=h1');
    req.flush({ content: [{}, {}], totalElements: 2, totalPages: 1, number: 0, size: 20 });
  });

  it('POSTs a provider review', () => {
    service.review('bp1', true, 'Dr. Smith', 'Looks good').subscribe();
    const req = httpMock.expectOne('/birth-plans/bp1/review');
    expect(req.request.body).toEqual({
      reviewed: true,
      signature: 'Dr. Smith',
      comments: 'Looks good',
    });
    req.flush({ id: 'bp1', providerReviewed: true });
  });

  it('DELETEs a plan', () => {
    service.delete('bp1').subscribe();
    const req = httpMock.expectOne('/birth-plans/bp1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
