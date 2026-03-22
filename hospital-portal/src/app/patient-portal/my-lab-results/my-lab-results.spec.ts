import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyLabResultsComponent } from './my-lab-results';

describe('MyLabResultsComponent', () => {
  let component: MyLabResultsComponent;
  let fixture: ComponentFixture<MyLabResultsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyLabResultsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyLabResultsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
