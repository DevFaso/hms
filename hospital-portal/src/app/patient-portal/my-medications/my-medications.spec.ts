import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyMedicationsComponent } from './my-medications';

describe('MyMedicationsComponent', () => {
  let component: MyMedicationsComponent;
  let fixture: ComponentFixture<MyMedicationsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyMedicationsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyMedicationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
