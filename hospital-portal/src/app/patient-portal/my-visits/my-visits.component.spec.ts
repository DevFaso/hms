import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyVisitsComponent } from './my-visits.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyVisitsComponent', () => {
  let component: MyVisitsComponent;
  let fixture: ComponentFixture<MyVisitsComponent>;

  const mockPortalService = {
    getMyEncounters: () => of([]),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyVisitsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyVisitsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no encounters', () => {
    expect(component.encounters().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should toggle expand', () => {
    expect(component.expandedId()).toBeNull();
    component.toggleExpand('v1');
    expect(component.expandedId()).toBe('v1');
    component.toggleExpand('v1');
    expect(component.expandedId()).toBeNull();
  });
});
