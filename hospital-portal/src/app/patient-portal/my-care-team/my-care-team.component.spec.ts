import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyCareTeamComponent } from './my-care-team.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyCareTeamComponent', () => {
  let component: MyCareTeamComponent;
  let fixture: ComponentFixture<MyCareTeamComponent>;

  const mockPortalService = {
    getMyCareTeam: () => of({ members: [] }),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyCareTeamComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyCareTeamComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no members', () => {
    expect(component.members().length).toBe(0);
    expect(component.loading()).toBe(false);
  });
});
