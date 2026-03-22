import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyCheckinComponent } from './my-checkin';

describe('MyCheckinComponent', () => {
  let component: MyCheckinComponent;
  let fixture: ComponentFixture<MyCheckinComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyCheckinComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyCheckinComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should start in loading state', () => {
    expect(component.loading()).toBeTrue();
  });

  it('should start with empty checked-in set', () => {
    expect(component.checkedIn().size).toBe(0);
  });

  it('should return correct status classes', () => {
    expect(component.statusClass('confirmed')).toBe('status-confirmed');
    expect(component.statusClass('scheduled')).toBe('status-scheduled');
    expect(component.statusClass('checked_in')).toBe('status-checked-in');
    expect(component.statusClass('pending')).toBe('status-pending');
  });
});
