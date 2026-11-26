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

  it('sends the work-queue sort keys one per parameter, and none by default (#963 review)', () => {
    service.getDispenseWorkQueue(0, 100, 'ALL', ['createdAt,desc', 'id,desc']).subscribe();
    const sorted = http.expectOne((r) => r.url === '/pharmacy/dispense/work-queue');
    expect(sorted.request.params.getAll('sort')).toEqual(['createdAt,desc', 'id,desc']);
    expect(sorted.request.params.get('size')).toBe('100');
    expect(sorted.request.params.has('claim')).toBeFalse();
    sorted.flush({ success: true, data: { content: [], totalElements: 0, totalPages: 0 } });

    service.getDispenseWorkQueue(1, 20, 'MINE').subscribe();
    const bare = http.expectOne((r) => r.url === '/pharmacy/dispense/work-queue');
    expect(bare.request.params.has('sort')).toBeFalse();
    expect(bare.request.params.get('claim')).toBe('MINE');
    bare.flush({ success: true, data: null });
  });

  it('QA D2319: a typed term searches the queue by POST, the term in the body and never in the URL', () => {
    service.getDispenseWorkQueue(0, 20, 'MINE', undefined, '  Kaboré ').subscribe();
    const search = http.expectOne((r) => r.url === '/pharmacy/dispense/work-queue/term-search');
    expect(search.request.method).toBe('POST');
    expect(search.request.body).toEqual({ search: 'Kaboré' });
    expect(search.request.params.get('claim')).toBe('MINE');
    expect(search.request.params.get('page')).toBe('0');
    expect(search.request.urlWithParams).not.toContain('Kabor');
    search.flush({ success: true, data: { content: [], totalElements: 0, totalPages: 0 } });

    // A blank term is the queue itself: the plain GET.
    service.getDispenseWorkQueue(0, 20, 'ALL', undefined, '   ').subscribe();
    const list = http.expectOne((r) => r.url === '/pharmacy/dispense/work-queue');
    expect(list.request.method).toBe('GET');
    list.flush({ success: true, data: null });
  });

  it('QA D2679: the witness’s secret is posted in the body of the confirmation call', () => {
    service.confirmWitness('w-1', 's3cret').subscribe();
    const call = http.expectOne('/pharmacy/stock-transactions/witness-confirmations');
    expect(call.request.method).toBe('POST');
    expect(call.request.body).toEqual({ witnessedBy: 'w-1', secret: 's3cret' });
    expect(call.request.urlWithParams).not.toContain('s3cret');
    call.flush({ success: true, data: null });
  });
});
