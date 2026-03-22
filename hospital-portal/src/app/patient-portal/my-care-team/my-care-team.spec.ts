import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyCareTeamComponent } from './my-care-team';

describe('MyCareTeamComponent', () => {
  let component: MyCareTeamComponent;
  let fixture: ComponentFixture<MyCareTeamComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyCareTeamComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyCareTeamComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
