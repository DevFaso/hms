import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { MortalityService } from './mortality.service';
import { OrderReviewService } from './order-review.service';
import { StoryboardService } from './storyboard.service';
import { BACKGROUND_REQUEST } from './ws-transport';

/**
 * QA D1524 (class: a chart side panel whose 403 sent the whole chart to /error/403). Every read a
 * side panel makes on chart load is a background request: the error interceptor then leaves the
 * refusal to the panel, which shows nothing, and the chart stays open.
 */
describe('chart side-panel reads (QA D1524)', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('the voided-death banner, the storyboard and the held-orders panel read in the background', () => {
    TestBed.inject(MortalityService).voidedClosures('p1').subscribe();
    TestBed.inject(StoryboardService).getStoryboard('p1', 'h1').subscribe();
    TestBed.inject(OrderReviewService).forPatient('p1').subscribe();

    const reads = [
      httpMock.expectOne((r) => r.url.endsWith('/patients/p1/voided-closures')),
      httpMock.expectOne((r) => r.url === '/patients/p1/storyboard'),
      httpMock.expectOne((r) => r.url.endsWith('/order-review-holds/patient/p1')),
    ];
    for (const read of reads) {
      expect(read.request.context.get(BACKGROUND_REQUEST)).withContext(read.request.url).toBeTrue();
      read.flush([]);
    }
    expect(reads[1].request.params.get('hospitalId')).toBe('h1');
  });
});
