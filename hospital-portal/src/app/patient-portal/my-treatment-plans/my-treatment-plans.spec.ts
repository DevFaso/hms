import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyTreatmentPlansComponent } from './my-treatment-plans';

describe('MyTreatmentPlansComponent', () => {
  let component: MyTreatmentPlansComponent;
  let fixture: ComponentFixture<MyTreatmentPlansComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyTreatmentPlansComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyTreatmentPlansComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading state initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('statusClass returns correct class for APPROVED', () => {
    expect(component.statusClass('APPROVED')).toBe('status-approved');
  });

  it('statusClass returns correct class for IN_REVIEW', () => {
    expect(component.statusClass('IN_REVIEW')).toBe('status-review');
  });

  it('statusClass returns correct class for CANCELLED', () => {
    expect(component.statusClass('CANCELLED')).toBe('status-inactive');
  });

  it('toggle expands and collapses a plan', () => {
    component.toggle('plan-1');
    expect(component.expandedId()).toBe('plan-1');
    component.toggle('plan-1');
    expect(component.expandedId()).toBeNull();
  });

  it('ratingClass returns rating-high for score >= 8', () => {
    expect(component.ratingClass(8)).toBe('rating-high');
    expect(component.ratingClass(10)).toBe('rating-high');
  });

  it('ratingClass returns rating-mid for score 5-7', () => {
    expect(component.ratingClass(5)).toBe('rating-mid');
    expect(component.ratingClass(7)).toBe('rating-mid');
  });

  it('ratingClass returns rating-low for score < 5', () => {
    expect(component.ratingClass(1)).toBe('rating-low');
    expect(component.ratingClass(4)).toBe('rating-low');
  });

  it('ratingClass returns empty string for null', () => {
    expect(component.ratingClass(null)).toBe('');
  });

  it('showLogForm starts empty', () => {
    expect(component.showLogForm()).toEqual({});
  });

  it('openLogForm sets showLogForm for planId', () => {
    component.openLogForm('p1');
    expect(component.showLogForm()['p1']).toBeTrue();
  });

  it('cancelLogForm hides form for planId', () => {
    component.openLogForm('p1');
    component.cancelLogForm('p1');
    expect(component.showLogForm()['p1']).toBeFalse();
  });
});
