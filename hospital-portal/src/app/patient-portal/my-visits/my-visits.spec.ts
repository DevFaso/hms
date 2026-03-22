import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyVisitsComponent } from './my-visits';

describe('MyVisitsComponent', () => {
  let component: MyVisitsComponent;
  let fixture: ComponentFixture<MyVisitsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyVisitsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyVisitsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
