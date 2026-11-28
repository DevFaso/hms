import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UrgentAlertsService } from './urgent-alerts.service';

describe('UrgentAlertsService', () => {
  let service: UrgentAlertsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UrgentAlertsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads an answer of another shape as no alert, never as a broken screen', () => {
    let lines: unknown[] | undefined;
    service.incidents().subscribe((read) => (lines = read));
    http.expectOne('/urgent-alerts/incidents').flush({ content: [], totalElements: 0 });
    expect(lines).toEqual([]);

    service.refreshMine();
    http.expectOne('/urgent-alerts/mine').flush({ content: [], totalElements: 0 });
    expect(service.mine().items).toEqual([]);
    expect(service.waiting()).toBe(0);

    service.refreshMarks();
    http.expectOne('/urgent-alerts/patients').flush({ content: [] });
    expect(service.marks().size).toBe(0);
  });
});
