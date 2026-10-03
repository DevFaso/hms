import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PharmacyService } from './pharmacy.service';

describe('PharmacyService', () => {
  let service: PharmacyService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(PharmacyService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends each routing-history sort key as its own parameter, tiebreaker included', () => {
    service
      .listRoutingDecisionsByPrescription('rx-1', 1, 10, ['decidedAt,desc', 'id,desc'])
      .subscribe();

    const req = http.expectOne((r) => r.url === '/pharmacy/routing/decisions/prescription/rx-1');
    expect(req.request.params.getAll('sort')).toEqual(['decidedAt,desc', 'id,desc']);
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ success: true, data: { content: [], totalElements: 0, totalPages: 0 } });
  });

  it('still takes a single sort key, and sends none when given none', () => {
    service.listRoutingDecisionsByPrescription('rx-1', 0, 10, 'decidedAt,desc').subscribe();
    http
      .expectOne((r) => r.params.getAll('sort')?.join('|') === 'decidedAt,desc')
      .flush({ success: true, data: null });

    service.listRoutingDecisionsByPrescription('rx-2').subscribe();
    const bare = http.expectOne((r) => r.url === '/pharmacy/routing/decisions/prescription/rx-2');
    expect(bare.request.params.has('sort')).toBeFalse();
    bare.flush({ success: true, data: null });
  });
});
