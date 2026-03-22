import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyConsultationsComponent } from './my-consultations';

describe('MyConsultationsComponent', () => {
  let component: MyConsultationsComponent;
  let fixture: ComponentFixture<MyConsultationsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyConsultationsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyConsultationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading state initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('statusClass returns completed for COMPLETED', () => {
    expect(component.statusClass('COMPLETED')).toBe('status-completed');
  });

  it('statusClass returns active for IN_PROGRESS', () => {
    expect(component.statusClass('IN_PROGRESS')).toBe('status-active');
  });

  it('urgencyClass returns urgent for STAT', () => {
    expect(component.urgencyClass('STAT')).toBe('urgency-urgent');
  });

  it('urgencyClass returns routine for ROUTINE', () => {
    expect(component.urgencyClass('ROUTINE')).toBe('urgency-routine');
  });
});
