import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyLabResultsComponent } from './my-lab-results.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyLabResultsComponent', () => {
  let component: MyLabResultsComponent;
  let fixture: ComponentFixture<MyLabResultsComponent>;

  const mockPortalService = {
    getMyLabResults: () => of([]),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyLabResultsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyLabResultsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no results', () => {
    expect(component.results().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle expand', () => {
    component.toggleExpand('l1');
    expect(component.expandedId()).toBe('l1');
    component.toggleExpand('l1');
    expect(component.expandedId()).toBeNull();
  });
});
