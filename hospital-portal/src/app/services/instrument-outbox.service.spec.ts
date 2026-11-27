import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { InstrumentOutboxService } from './instrument-outbox.service';

describe('InstrumentOutboxService hospital context', () => {
  it('sends the row hospital for detail, siblings and retry', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const service = TestBed.inject(InstrumentOutboxService);
    const http = TestBed.inject(HttpTestingController);
    service.getMessage('m', 'h2').subscribe();
    service.getMessagesForOrder('o', 'h2').subscribe();
    service.retry('m', 'h2').subscribe();
    for (const url of [
      '/lab-instrument-outbox/m',
      '/lab-instrument-outbox/orders/o',
      '/lab-instrument-outbox/m/retry',
    ]) {
      const request = http.expectOne(url);
      expect(request.request.headers.get('X-Hospital-Id')).toBe('h2');
      request.flush({ data: url.includes('/orders/') ? [] : {} });
    }
    http.verify();
  });
});
