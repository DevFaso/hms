import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyEducationProgressComponent } from './my-education-progress';

describe('MyEducationProgressComponent', () => {
  let component: MyEducationProgressComponent;
  let fixture: ComponentFixture<MyEducationProgressComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyEducationProgressComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyEducationProgressComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should default filter to all', () => {
    expect(component.filter()).toBe('all');
  });

  it('setFilter should switch to in_progress', () => {
    component.setFilter('in_progress');
    expect(component.filter()).toBe('in_progress');
  });

  it('setFilter should switch to completed', () => {
    component.setFilter('completed');
    expect(component.filter()).toBe('completed');
  });

  it('should map status icon labels correctly', () => {
    expect(component.statusIcon('COMPLETED')).toBe('task_alt');
    expect(component.statusIcon('IN_PROGRESS')).toBe('pending');
    expect(component.statusIcon('NOT_STARTED')).toBe('school');
  });

  it('should map comprehension labels', () => {
    expect(component.comprehensionLabel('COMPLETED')).toBe('Completed');
    expect(component.comprehensionLabel('IN_PROGRESS')).toBe('In Progress');
    expect(component.comprehensionLabel('NOT_STARTED')).toBe('Not Started');
  });

  it('formatTime should format seconds, minutes, hours', () => {
    expect(component.formatTime(45)).toBe('45s');
    expect(component.formatTime(120)).toBe('2m');
    expect(component.formatTime(3660)).toBe('1h 1m');
  });
});
