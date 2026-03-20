import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyAdmissionsComponent } from './my-admissions';

describe('MyAdmissionsComponent', () => {
  let component: MyAdmissionsComponent;
  let fixture: ComponentFixture<MyAdmissionsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyAdmissionsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyAdmissionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
