import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyRefillsComponent } from './my-refills';

describe('MyRefillsComponent', () => {
  let component: MyRefillsComponent;
  let fixture: ComponentFixture<MyRefillsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyRefillsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyRefillsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading state initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('should start with form hidden', () => {
    expect(component.showForm()).toBeFalse();
  });

  it('openForm sets showForm to true', () => {
    component.openForm();
    expect(component.showForm()).toBeTrue();
  });

  it('cancelForm hides form', () => {
    component.openForm();
    component.cancelForm();
    expect(component.showForm()).toBeFalse();
  });

  it('statusClass returns dispensed for DISPENSED', () => {
    expect(component.statusClass('DISPENSED')).toBe('status-dispensed');
  });

  it('statusClass returns approved for APPROVED', () => {
    expect(component.statusClass('APPROVED')).toBe('status-approved');
  });

  it('statusClass returns pending for PENDING', () => {
    expect(component.statusClass('PENDING')).toBe('status-pending');
  });

  it('statusClass returns cancelled for CANCELLED', () => {
    expect(component.statusClass('CANCELLED')).toBe('status-cancelled');
  });
});
