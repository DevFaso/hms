import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyPharmacyFillsComponent } from './my-pharmacy-fills';

describe('MyPharmacyFillsComponent', () => {
  let component: MyPharmacyFillsComponent;
  let fixture: ComponentFixture<MyPharmacyFillsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyPharmacyFillsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyPharmacyFillsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
