import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { LabOpsDashboardComponent } from './lab-ops-dashboard';
import { LabOpsSummary } from '../../services/dashboard.service';

const MOCK_SUMMARY: LabOpsSummary = {
  hospitalId: '00000000-0000-0000-0000-000000000001',
  asOfDate: '2025-01-15',
  ordersToday: 42,
  completedToday: 30,
  cancelledToday: 2,
  ordersThisWeek: 180,
  completedThisWeek: 140,
  ordersThisMonth: 620,
  statusOrdered: 5,
  statusPending: 4,
  statusCollected: 8,
  statusReceived: 10,
  statusInProgress: 12,
  statusResulted: 3,
  statusVerified: 2,
  priorityRoutine: 30,
  priorityUrgent: 10,
  priorityStat: 4,
  avgTurnaroundMinutesToday: 45.7,
  avgTurnaroundMinutesThisWeek: 38.2,
  ordersOlderThan24h: 3,
};

describe('LabOpsDashboardComponent', () => {
  let fixture: ComponentFixture<LabOpsDashboardComponent>;
  let component: LabOpsDashboardComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabOpsDashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });

    fixture = TestBed.createComponent(LabOpsDashboardComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushSummary(data: LabOpsSummary = MOCK_SUMMARY): void {
    const req = httpMock.expectOne('/api/dashboard/lab-ops/summary');
    expect(req.request.method).toBe('GET');
    req.flush(data);
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    httpMock.expectOne('/api/dashboard/lab-ops/summary');
  });

  it('should start in loading state', () => {
    expect(component.loading()).toBeTrue();
    fixture.detectChanges();
    httpMock.expectOne('/api/dashboard/lab-ops/summary');
  });

  it('should fetch summary on init and stop loading', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    expect(component.loading()).toBeFalse();
    expect(component.summary()).toEqual(MOCK_SUMMARY);
  });

  it('should compute 6 stat cards from summary', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const cards = component.statCards();
    expect(cards.length).toBe(6);
    expect(cards[0].key).toBe('orders_today');
    expect(cards[0].value).toBe(42);
    expect(cards[1].key).toBe('completed_today');
    expect(cards[1].value).toBe(30);
    expect(cards[2].key).toBe('avg_tat');
    expect(cards[2].value).toBe('46 min'); // 45.7 rounded
  });

  it('should show N/A for TAT when null', () => {
    fixture.detectChanges();
    flushSummary({ ...MOCK_SUMMARY, avgTurnaroundMinutesToday: null });
    fixture.detectChanges();

    const tatCard = component.statCards().find((c) => c.key === 'avg_tat');
    expect(tatCard?.value).toBe('N/A');
  });

  it('should compute 7 status rows', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const rows = component.statusRows();
    expect(rows.length).toBe(7);
    expect(rows[0].label).toBe('Ordered');
    expect(rows[0].count).toBe(5);
  });

  it('should compute status percentages correctly', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const rows = component.statusRows();
    // Total active = 5+4+8+10+12+3+2 = 44
    // Ordered pct = round(5/44*100) = 11
    expect(rows[0].pct).toBe(11);
  });

  it('should compute 3 priority rows', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const rows = component.priorityRows();
    expect(rows.length).toBe(3);
    expect(rows[0].label).toBe('Routine');
    expect(rows[0].count).toBe(30);
    expect(rows[1].label).toBe('Urgent');
    expect(rows[2].label).toBe('STAT');
  });

  it('should compute 4 throughput cards', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const cards = component.throughputCards();
    expect(cards.length).toBe(4);
    expect(cards[0].label).toBe('Completed This Week');
    expect(cards[0].value).toBe(140);
  });

  it('should handle API error gracefully', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/dashboard/lab-ops/summary');
    req.error(new ProgressEvent('error'));
    fixture.detectChanges();

    expect(component.loading()).toBeFalse();
    // catchError in DashboardService returns {} as LabOpsSummary
    expect(component.summary()).toBeTruthy();
  });

  it('should highlight aging card in red when > 0', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const agingCard = component.statCards().find((c) => c.key === 'aging');
    expect(agingCard?.color).toBe('#dc2626');
  });

  it('should show aging card in grey when 0', () => {
    fixture.detectChanges();
    flushSummary({ ...MOCK_SUMMARY, ordersOlderThan24h: 0 });
    fixture.detectChanges();

    const agingCard = component.statCards().find((c) => c.key === 'aging');
    expect(agingCard?.color).toBe('#64748b');
  });

  it('should render stat cards in the DOM', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('.stat-card');
    expect(cards.length).toBe(6);
  });

  it('should render status bar rows', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.bar-row');
    // 7 status rows + 3 priority rows = 10
    expect(rows.length).toBe(10);
  });

  it('should render throughput items', () => {
    fixture.detectChanges();
    flushSummary();
    fixture.detectChanges();

    const items = fixture.nativeElement.querySelectorAll('.throughput-item');
    expect(items.length).toBe(4);
  });

  it('should show loading spinner when loading', () => {
    fixture.detectChanges();

    const spinner = fixture.nativeElement.querySelector('.loading-overlay');
    expect(spinner).toBeTruthy();

    httpMock.expectOne('/api/dashboard/lab-ops/summary');
  });
});
