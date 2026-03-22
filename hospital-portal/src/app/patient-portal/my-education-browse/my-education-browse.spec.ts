import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyEducationBrowseComponent } from './my-education-browse';

describe('MyEducationBrowseComponent', () => {
  let component: MyEducationBrowseComponent;
  let fixture: ComponentFixture<MyEducationBrowseComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyEducationBrowseComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyEducationBrowseComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should start in loading state', () => {
    expect(component.loading()).toBeTrue();
  });

  it('should format category names', () => {
    expect(component.formatCategory('GENERAL_HEALTH')).toBe('General Health');
    expect(component.formatCategory('CHRONIC_DISEASE')).toBe('Chronic Disease');
  });

  it('should return correct difficulty class', () => {
    expect(component.difficultyClass('EASY')).toBe('easy');
    expect(component.difficultyClass('MEDIUM')).toBe('medium');
    expect(component.difficultyClass('HARD')).toBe('hard advanced');
    expect(component.difficultyClass('BEGINNER')).toBe('easy');
  });

  it('should default selected category empty', () => {
    expect(component.selectedCategory()).toBe('');
  });

  it('should set selected category when selecting', () => {
    component.selectCategory('GENERAL_HEALTH');
    expect(component.selectedCategory()).toBe('GENERAL_HEALTH');
  });

  it('clearSearch should clear query', () => {
    component.searchQuery = 'heart';
    component.clearSearch();
    expect(component.searchQuery).toBe('');
  });

  it('visible should reflect resources list', () => {
    component.resources.set([
      { id: '1', title: 'A', category: 'GENERAL_HEALTH', resourceType: 'ARTICLE' } as never,
    ]);
    expect(component.visible().length).toBe(1);
  });
});
