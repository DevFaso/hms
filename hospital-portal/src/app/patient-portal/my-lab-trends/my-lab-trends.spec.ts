import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyLabTrendsComponent } from './my-lab-trends';

describe('MyLabTrendsComponent', () => {
  let component: MyLabTrendsComponent;
  let fixture: ComponentFixture<MyLabTrendsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyLabTrendsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyLabTrendsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should start in loading state', () => {
    expect(component.loading()).toBeTrue();
  });

  it('should return correct status class for CRITICAL', () => {
    expect(component.statusClass('CRITICAL', false)).toBe('status-critical');
  });

  it('should return status-abnormal when abnormal flag is true', () => {
    expect(component.statusClass('NORMAL', true)).toBe('status-abnormal');
  });

  it('should return status-normal for normal non-abnormal result', () => {
    expect(component.statusClass('NORMAL', false)).toBe('status-normal');
  });

  it('should return status-pending for PENDING status', () => {
    expect(component.statusClass('PENDING', false)).toBe('status-pending');
  });
});
