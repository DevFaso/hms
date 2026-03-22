import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MyFamilyAccessComponent } from './my-family-access';

describe('MyFamilyAccessComponent', () => {
  let component: MyFamilyAccessComponent;
  let fixture: ComponentFixture<MyFamilyAccessComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyFamilyAccessComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(MyFamilyAccessComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
