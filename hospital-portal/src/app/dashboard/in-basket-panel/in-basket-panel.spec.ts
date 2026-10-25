import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { InBasketPanelComponent } from './in-basket-panel';
import {
  InBasketService,
  InBasketPage,
  InBasketSummary,
  InBasketItem,
} from '../../services/in-basket.service';
import { TranslateModule } from '@ngx-translate/core';
import { RoleContextService } from '../../core/role-context.service';

import { resetStoredLangForTests } from '../../shared/i18n/app-locale';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
function mockItem(overrides: Partial<InBasketItem> = {}): InBasketItem {
  return {
    id: 'ib-1',
    itemType: 'RESULT',
    priority: 'CRITICAL',
    status: 'UNREAD',
    title: 'CRITICAL: Troponin I > 2.0',
    body: 'Value: 2.5 ng/mL',
    referenceId: 'ref-1',
    referenceType: 'LAB_RESULT',
    encounterId: 'enc-1',
    patientId: 'pat-1',
    patientName: 'John Doe',
    orderingProviderName: 'Dr. Smith',
    createdAt: '2025-07-15T10:00:00',
    readAt: null,
    acknowledgedAt: null,
    ...overrides,
  };
}

function mockSummary(): InBasketSummary {
  return { totalUnread: 3, resultUnread: 2, orderUnread: 1, messageUnread: 0, taskUnread: 0 };
}

function mockPage(items: InBasketItem[] = [mockItem()]): InBasketPage {
  return { content: items, totalElements: items.length, totalPages: 1, number: 0, size: 20 };
}

describe('InBasketPanelComponent', () => {
  let component: InBasketPanelComponent;
  let fixture: ComponentFixture<InBasketPanelComponent>;
  let inBasketSpy: jasmine.SpyObj<InBasketService>;

  beforeEach(async () => {
    inBasketSpy = jasmine.createSpyObj('InBasketService', [
      'getItems',
      'getSummary',
      'markAsRead',
      'acknowledge',
    ]);

    inBasketSpy.getItems.and.returnValue(of(mockPage()));
    inBasketSpy.getSummary.and.returnValue(of(mockSummary()));

    await TestBed.configureTestingModule({
      imports: [InBasketPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: InBasketService, useValue: inBasketSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(InBasketPanelComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('reloads items and the unread badge when the hospital scope changes, dropping the old rows first', () => {
    const roleContext = TestBed.inject(RoleContextService);
    roleContext.activeHospitalId = 'hospital-a';
    fixture.detectChanges();
    expect(inBasketSpy.getItems).toHaveBeenCalledTimes(1);
    expect(component.items().length).toBe(1);

    // The next read, issued under hospital B, has not answered yet.
    inBasketSpy.getItems.and.returnValue(of(mockPage([])));
    inBasketSpy.getSummary.and.returnValue(of({ ...mockSummary(), totalUnread: 0 }));
    roleContext.activeHospitalId = 'hospital-b';
    fixture.detectChanges();

    expect(inBasketSpy.getItems).toHaveBeenCalledTimes(2);
    expect(inBasketSpy.getSummary).toHaveBeenCalledTimes(2);
    expect(component.items()).toEqual([]);
    expect(component.badgeCount()).toBe(0);
  });

  it('should load items and summary on init', () => {
    fixture.detectChanges();
    expect(inBasketSpy.getItems).toHaveBeenCalled();
    expect(inBasketSpy.getSummary).toHaveBeenCalled();
    expect(component.items().length).toBe(1);
    expect(component.summary().totalUnread).toBe(3);
  });

  it('should set loading to false after load', () => {
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
  });

  it('should handle load error gracefully', () => {
    inBasketSpy.getItems.and.returnValue(throwError(() => new Error('fail')));
    fixture.detectChanges();
    expect(component.loading()).toBeFalse();
    expect(component.items().length).toBe(0);
  });

  it('should filter items by type', () => {
    const items = [
      mockItem({ id: '1', itemType: 'RESULT' }),
      mockItem({ id: '2', itemType: 'ORDER' }),
      mockItem({ id: '3', itemType: 'RESULT' }),
    ];
    inBasketSpy.getItems.and.returnValue(of(mockPage(items)));
    fixture.detectChanges();

    component.setFilter('RESULT');
    expect(component.filteredItems().length).toBe(2);

    component.setFilter(null);
    expect(component.filteredItems().length).toBe(3);
  });

  it('should badge count reflect total unread', () => {
    fixture.detectChanges();
    expect(component.badgeCount()).toBe(3);
  });

  it('should mark item as read', () => {
    const updatedItem = mockItem({ status: 'READ', readAt: '2025-07-15T10:05:00' });
    inBasketSpy.markAsRead.and.returnValue(of(updatedItem));
    fixture.detectChanges();

    component.markRead(component.items()[0]);
    expect(inBasketSpy.markAsRead).toHaveBeenCalledWith('ib-1');
  });

  it('should not mark already-read item', () => {
    const readItem = mockItem({ status: 'READ' });
    inBasketSpy.getItems.and.returnValue(of(mockPage([readItem])));
    fixture.detectChanges();

    component.markRead(component.items()[0]);
    expect(inBasketSpy.markAsRead).not.toHaveBeenCalled();
  });

  it('should acknowledge item', () => {
    const ackItem = mockItem({ status: 'ACKNOWLEDGED', acknowledgedAt: '2025-07-15T10:10:00' });
    inBasketSpy.acknowledge.and.returnValue(of(ackItem));
    fixture.detectChanges();

    component.acknowledgeItem(component.items()[0]);
    expect(inBasketSpy.acknowledge).toHaveBeenCalledWith('ib-1');
  });

  it('should not acknowledge already-acknowledged item', () => {
    const ackItem = mockItem({ status: 'ACKNOWLEDGED' });
    inBasketSpy.getItems.and.returnValue(of(mockPage([ackItem])));
    fixture.detectChanges();

    component.acknowledgeItem(component.items()[0]);
    expect(inBasketSpy.acknowledge).not.toHaveBeenCalled();
  });

  it('should return correct priority class', () => {
    expect(component.priorityClass('CRITICAL')).toBe('priority-critical');
    expect(component.priorityClass('URGENT')).toBe('priority-urgent');
    expect(component.priorityClass('NORMAL')).toBe('priority-normal');
  });

  it('should return correct type icon', () => {
    expect(component.typeIcon('RESULT')).toBe('🧪');
    expect(component.typeIcon('ORDER')).toBe('📋');
    expect(component.typeIcon('MESSAGE')).toBe('✉️');
    expect(component.typeIcon('TASK')).toBe('✅');
    expect(component.typeIcon('UNKNOWN')).toBe('📌');
  });

  it('formats dates in the product language by default — French, 24-hour', () => {
    localStorage.removeItem('lang');
    resetStoredLangForTests();
    expect(component.formatDate(null)).toBe('');
    const out = component.formatDate('2025-07-15T10:00:00');
    expect(out).toContain('juil.');
    const local = new Date(Date.UTC(2025, 6, 15, 10, 0));
    expect(out).toContain(`${local.getHours()}:${String(local.getMinutes()).padStart(2, '0')}`);
    expect(out).not.toContain('AM');
  });

  it("QA D662 (D647): the inbox shows the server's UTC time in the reader's zone", () => {
    // The server sends a zone-less UTC wall time. Read raw, the browser took
    // it for local time and New York saw 18:03 for a 14:03 event. Asserting
    // that it is marked UTC keeps the test meaningful on a machine set to UTC.
    const transform = spyOn(ServerTimePipe.prototype, 'transform').and.callThrough();
    inBasketSpy.getItems.and.returnValue(
      of(mockPage([mockItem({ createdAt: '2026-10-23T18:03:00' })])),
    );
    fixture.detectChanges();

    expect(transform).toHaveBeenCalledWith('2026-10-23T18:03:00');
    expect(transform.calls.mostRecent().returnValue).toBe('2026-10-23T18:03:00Z');
    const shown =
      (fixture.nativeElement as HTMLElement).querySelector('.meta-time')?.textContent ?? '';
    const local = new Date(Date.UTC(2026, 9, 23, 18, 3));
    expect(shown).toContain(`${local.getHours()}:${String(local.getMinutes()).padStart(2, '0')}`);
  });

  it('follows the stored language when the user chose English', () => {
    localStorage.setItem('lang', 'en');
    resetStoredLangForTests();
    try {
      expect(component.formatDate('2025-07-15T10:00:00')).toContain('Jul');
    } finally {
      localStorage.removeItem('lang');
      resetStoredLangForTests();
    }
  });

  it('should trackById return item id', () => {
    const item = mockItem();
    expect(component.trackById(0, item)).toBe('ib-1');
  });

  it('should refresh reloads data', () => {
    fixture.detectChanges();
    inBasketSpy.getItems.calls.reset();
    inBasketSpy.getSummary.calls.reset();

    component.refresh();
    expect(inBasketSpy.getItems).toHaveBeenCalled();
    expect(inBasketSpy.getSummary).toHaveBeenCalled();
  });
});
