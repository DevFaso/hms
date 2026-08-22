import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { DowntimeBannerComponent } from './downtime-banner';
import { DowntimeService } from '../services/downtime.service';

describe('DowntimeBannerComponent (P3 #23a)', () => {
  let downtime: DowntimeService;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DowntimeBannerComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    downtime = TestBed.inject(DowntimeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    downtime.stopPolling();
    httpMock.verify();
  });

  it('is hidden during normal operation', () => {
    const fixture = TestBed.createComponent(DowntimeBannerComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="downtime-banner"]')).toBeNull();
  });

  it('shows the persisted state — a late login still sees the banner', () => {
    const fixture = TestBed.createComponent(DowntimeBannerComponent);
    downtime.load();
    httpMock
      .expectOne('/downtime/status')
      .flush({ readOnly: true, message: 'Switch upgrade until 14:00', activatedAt: null });
    fixture.detectChanges();

    const banner = fixture.nativeElement.querySelector('[data-testid="downtime-banner"]');
    expect(banner).toBeTruthy();
    expect(banner.textContent).toContain('Switch upgrade until 14:00');
  });

  it('appears immediately when the interceptor marks read-only', () => {
    const fixture = TestBed.createComponent(DowntimeBannerComponent);
    downtime.markReadOnly('Writes are disabled');
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="downtime-banner"]').textContent,
    ).toContain('Writes are disabled');
  });

  it('a failed poll keeps the last known state — not "downtime over"', () => {
    downtime.markReadOnly('hold');
    downtime.load();
    httpMock.expectOne('/downtime/status').error(new ProgressEvent('error'), { status: 0 });

    expect(downtime.status()?.readOnly).toBeTrue();
  });
});
