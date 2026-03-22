import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyUpcomingVaccinesComponent } from './my-upcoming-vaccines';

describe('MyUpcomingVaccinesComponent', () => {
  let component: MyUpcomingVaccinesComponent;
  let fixture: ComponentFixture<MyUpcomingVaccinesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyUpcomingVaccinesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyUpcomingVaccinesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
