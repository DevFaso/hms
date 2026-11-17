import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DashboardService, emptyRecentActivityBundle } from './dashboard.service';

describe('Dashboard recent activity availability', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    TestBed.resetTestingModule();
  });
  it('preserves successful empty data and exposes transport failures as unavailable', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const service = TestBed.inject(DashboardService);
    const http = TestBed.inject(HttpTestingController);
    service
      .getRecentActivity()
      .subscribe((result) => expect(result).toEqual(emptyRecentActivityBundle()));
    http.expectOne('/api/super-admin/recent-activity?limit=10').flush(emptyRecentActivityBundle());
    spyOn(console, 'error');
    service.getRecentActivity().subscribe((result) => expect(result).toBeNull());
    http
      .expectOne('/api/super-admin/recent-activity?limit=10')
      .flush({}, { status: 503, statusText: 'Unavailable' });
  });
});
