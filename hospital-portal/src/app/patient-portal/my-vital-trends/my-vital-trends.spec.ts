import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyVitalTrendsComponent } from './my-vital-trends';

describe('MyVitalTrendsComponent', () => {
  let component: MyVitalTrendsComponent;
  let fixture: ComponentFixture<MyVitalTrendsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyVitalTrendsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyVitalTrendsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
