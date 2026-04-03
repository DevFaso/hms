import { ComponentFixture, TestBed } from '@angular/core/testing';
import { InsuranceIssuesPanelComponent } from './insurance-issues-panel.component';
import { TranslateModule } from '@ngx-translate/core';

describe('InsuranceIssuesPanelComponent', () => {
  let component: InsuranceIssuesPanelComponent;
  let fixture: ComponentFixture<InsuranceIssuesPanelComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InsuranceIssuesPanelComponent, TranslateModule.forRoot()],
    }).compileComponents();

    fixture = TestBed.createComponent(InsuranceIssuesPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no issues', () => {
    component.issues = [];
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector('.empty-state');
    expect(el).toBeTruthy();
  });

  it('should return chip-danger for MISSING_INSURANCE', () => {
    expect(component.issueClass('MISSING_INSURANCE')).toBe('chip-danger');
  });

  it('should return chip-warn for other issue types', () => {
    expect(component.issueClass('EXPIRED_INSURANCE')).toBe('chip-warn');
    expect(component.issueClass('NO_PRIMARY')).toBe('chip-warn');
  });

  it('should emit patientClicked', () => {
    spyOn(component.patientClicked, 'emit');
    component.patientClicked.emit('p1');
    expect(component.patientClicked.emit).toHaveBeenCalledWith('p1');
  });
});
