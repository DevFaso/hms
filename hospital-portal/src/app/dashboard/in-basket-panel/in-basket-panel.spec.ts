import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
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
        provideHttpClient(),
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

  it('should format dates correctly', () => {
    expect(component.formatDate(null)).toBe('');
    expect(component.formatDate('2025-07-15T10:00:00')).toContain('Jul');
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
