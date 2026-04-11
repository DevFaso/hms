import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { InBasketService, InBasketPage, InBasketSummary, InBasketItem } from './in-basket.service';

describe('InBasketService', () => {
  let service: InBasketService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [InBasketService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(InBasketService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  describe('getItems', () => {
    it('should GET /api/in-basket with default params', () => {
      const mockPage: InBasketPage = {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 20,
      };

      service.getItems().subscribe((page) => {
        expect(page.content.length).toBe(0);
      });

      const req = httpMock.expectOne((r) => r.url === '/api/in-basket');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush(mockPage);
    });

    it('should pass optional filter params', () => {
      service.getItems('h1', 'RESULT', 'UNREAD', 1, 10).subscribe();

      const req = httpMock.expectOne((r) => r.url === '/api/in-basket');
      expect(req.request.params.get('hospitalId')).toBe('h1');
      expect(req.request.params.get('type')).toBe('RESULT');
      expect(req.request.params.get('status')).toBe('UNREAD');
      expect(req.request.params.get('page')).toBe('1');
      expect(req.request.params.get('size')).toBe('10');
      req.flush({ content: [], totalElements: 0, totalPages: 0, number: 1, size: 10 });
    });
  });

  describe('getSummary', () => {
    it('should GET /api/in-basket/summary', () => {
      const mockSummary: InBasketSummary = {
        totalUnread: 5,
        resultUnread: 3,
        orderUnread: 1,
        messageUnread: 0,
        taskUnread: 1,
      };

      service.getSummary().subscribe((s) => {
        expect(s.totalUnread).toBe(5);
        expect(s.resultUnread).toBe(3);
      });

      const req = httpMock.expectOne('/api/in-basket/summary');
      expect(req.request.method).toBe('GET');
      req.flush(mockSummary);
    });

    it('should pass hospitalId when provided', () => {
      service.getSummary('h1').subscribe();
      const req = httpMock.expectOne((r) => r.url === '/api/in-basket/summary');
      expect(req.request.params.get('hospitalId')).toBe('h1');
      req.flush({
        totalUnread: 0,
        resultUnread: 0,
        orderUnread: 0,
        messageUnread: 0,
        taskUnread: 0,
      });
    });
  });

  describe('markAsRead', () => {
    it('should PUT /api/in-basket/:id/read', () => {
      const mockItem = { id: 'item-1', status: 'READ' } as InBasketItem;

      service.markAsRead('item-1').subscribe((item) => {
        expect(item.status).toBe('READ');
      });

      const req = httpMock.expectOne('/api/in-basket/item-1/read');
      expect(req.request.method).toBe('PUT');
      req.flush(mockItem);
    });
  });

  describe('acknowledge', () => {
    it('should PUT /api/in-basket/:id/acknowledge', () => {
      const mockItem = { id: 'item-1', status: 'ACKNOWLEDGED' } as InBasketItem;

      service.acknowledge('item-1').subscribe((item) => {
        expect(item.status).toBe('ACKNOWLEDGED');
      });

      const req = httpMock.expectOne('/api/in-basket/item-1/acknowledge');
      expect(req.request.method).toBe('PUT');
      req.flush(mockItem);
    });
  });
});
