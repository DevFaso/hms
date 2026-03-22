import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyLabOrdersComponent } from './my-lab-orders';

describe('MyLabOrdersComponent', () => {
  let component: MyLabOrdersComponent;
  let fixture: ComponentFixture<MyLabOrdersComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyLabOrdersComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyLabOrdersComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
