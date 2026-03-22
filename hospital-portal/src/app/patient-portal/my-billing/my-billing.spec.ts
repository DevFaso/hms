import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyBillingComponent } from './my-billing';

describe('MyBillingComponent', () => {
  let component: MyBillingComponent;
  let fixture: ComponentFixture<MyBillingComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyBillingComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyBillingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
