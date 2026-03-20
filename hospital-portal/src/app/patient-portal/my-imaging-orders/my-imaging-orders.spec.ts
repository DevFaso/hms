import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyImagingOrdersComponent } from './my-imaging-orders';

describe('MyImagingOrdersComponent', () => {
  let component: MyImagingOrdersComponent;
  let fixture: ComponentFixture<MyImagingOrdersComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyImagingOrdersComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyImagingOrdersComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
