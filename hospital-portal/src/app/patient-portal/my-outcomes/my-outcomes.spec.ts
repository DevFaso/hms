import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyOutcomesComponent } from './my-outcomes';

describe('MyOutcomesComponent', () => {
  let component: MyOutcomesComponent;
  let fixture: ComponentFixture<MyOutcomesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyOutcomesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyOutcomesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show loading state initially', () => {
    expect(component.loading()).toBeTrue();
  });

  it('showForm is false by default', () => {
    expect(component.showForm()).toBeFalse();
  });

  it('openForm sets showForm to true', () => {
    component.openForm();
    expect(component.showForm()).toBeTrue();
  });

  it('cancelForm hides the form', () => {
    component.openForm();
    component.cancelForm();
    expect(component.showForm()).toBeFalse();
  });

  it('openForm resets form to defaults', () => {
    component.form.score = 2;
    component.form.notes = 'old note';
    component.openForm();
    expect(component.form.score).toBe(7);
    expect(component.form.notes).toBe('');
  });

  it('scoreClass returns score-good for score >= 7', () => {
    expect(component.scoreClass(7)).toBe('score-good');
    expect(component.scoreClass(10)).toBe('score-good');
  });

  it('scoreClass returns score-mid for score 4-6', () => {
    expect(component.scoreClass(4)).toBe('score-mid');
    expect(component.scoreClass(6)).toBe('score-mid');
  });

  it('scoreClass returns score-poor for score < 4', () => {
    expect(component.scoreClass(0)).toBe('score-poor');
    expect(component.scoreClass(3)).toBe('score-poor');
  });

  it('outcomeTypes contains all 10 types', () => {
    expect(component.outcomeTypes.length).toBe(10);
  });

  it('outcomeTypes includes GENERAL_WELLBEING', () => {
    const found = component.outcomeTypes.find((t) => t.value === 'GENERAL_WELLBEING');
    expect(found).toBeTruthy();
    expect(found!.label).toBe('General Wellbeing');
  });
});
