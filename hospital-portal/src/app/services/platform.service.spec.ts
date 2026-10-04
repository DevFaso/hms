import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PlatformService } from './platform.service';

describe('PlatformService', () => {
  let service: PlatformService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PlatformService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('switches an existing hospital link with PUT {enabled} — never a second create (D2)', () => {
    service.setHospitalLinkEnabled('h1', 's1', true).subscribe();

    const req = http.expectOne('/platform/hospitals/h1/services/s1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: true });
    req.flush({});
  });

  it('creates a link with POST and removes one with DELETE', () => {
    service.linkHospital('h1', 's1', { enabled: true }).subscribe();
    const create = http.expectOne('/platform/hospitals/h1/services/s1');
    expect(create.request.method).toBe('POST');
    create.flush({});

    service.unlinkHospital('h1', 's1').subscribe();
    const remove = http.expectOne('/platform/hospitals/h1/services/s1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });

  it('lists a service’s links across every hospital in one call (D3)', () => {
    service.listServiceHospitalLinks('o1', 's1').subscribe((links) => {
      expect(links.length).toBe(2);
    });

    const req = http.expectOne('/platform/organizations/o1/services/s1/hospital-links');
    expect(req.request.method).toBe('GET');
    req.flush([{ hospitalId: 'h1' }, { hospitalId: 'h2' }]);
  });

  it('reads release windows from the server, with an optional limit (D6)', () => {
    service.listReleaseWindows().subscribe();
    const all = http.expectOne((r) => r.url === '/super-admin/platform/release-windows');
    expect(all.request.params.has('limit')).toBeFalse();
    all.flush([]);

    service.listReleaseWindows(20).subscribe();
    const limited = http.expectOne((r) => r.url === '/super-admin/platform/release-windows');
    expect(limited.request.params.get('limit')).toBe('20');
    limited.flush([]);
  });

  it('sends the update body as given, so an emptied field reaches the server as "" (D5)', () => {
    service.updateOrgService('o1', 's1', { provider: '', clearApiKeyReference: true }).subscribe();

    const req = http.expectOne('/platform/organizations/o1/services/s1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ provider: '', clearApiKeyReference: true });
    req.flush({});
  });

  it('keeps the existing reads and writes on their routes', () => {
    service.getCatalog(false).subscribe();
    expect(
      http.expectOne((r) => r.url === '/platform/catalog').request.params.get('includeDisabled'),
    ).toBe('false');

    service.getCatalogItem('EHR').subscribe();
    http.expectOne('/platform/catalog/EHR').flush({});

    service.getSummary().subscribe();
    http.expectOne('/super-admin/platform/registry/summary').flush({});

    service.getSnapshot().subscribe();
    http.expectOne('/super-admin/platform/registry/snapshot').flush({});

    service.listOrgServices('o1', 'ACTIVE').subscribe();
    expect(
      http
        .expectOne((r) => r.url === '/platform/organizations/o1/services')
        .request.params.get('status'),
    ).toBe('ACTIVE');

    service.getOrgService('o1', 's1').subscribe();
    http.expectOne('/platform/organizations/o1/services/s1').flush({});

    service.registerOrgService('o1', { serviceType: 'EHR' }).subscribe();
    expect(http.expectOne('/platform/organizations/o1/services').request.method).toBe('POST');

    service.listHospitalLinks('h1').subscribe();
    http.expectOne('/platform/hospitals/h1/services').flush([]);

    service.listDepartmentLinks('d1').subscribe();
    http.expectOne('/platform/departments/d1/services').flush([]);

    service.linkDepartment('d1', 's1').subscribe();
    expect(http.expectOne('/platform/departments/d1/services/s1').request.body).toEqual({});

    service.unlinkDepartment('d1', 's1').subscribe();
    expect(http.expectOne('/platform/departments/d1/services/s1').request.method).toBe('DELETE');

    service
      .scheduleReleaseWindow({ name: 'w', environment: 'staging', startsAt: 'a', endsAt: 'b' })
      .subscribe();
    expect(http.expectOne('/super-admin/platform/release-windows').request.method).toBe('POST');
  });
});
