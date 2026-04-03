import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MySummariesComponent } from './my-summaries.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MySummariesComponent', () => {
  let component: MySummariesComponent;
  let fixture: ComponentFixture<MySummariesComponent>;

  const mockPortalService = {
    getAfterVisitSummaries: () => of([]),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MySummariesComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MySummariesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no summaries', () => {
    expect(component.summaries().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle expand', () => {
    expect(component.expandedId()).toBeNull();
    component.toggle('s1');
    expect(component.expandedId()).toBe('s1');
    component.toggle('s1');
    expect(component.expandedId()).toBeNull();
  });
});
