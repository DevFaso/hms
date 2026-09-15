import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { InBasketPageComponent } from './in-basket-page';

describe('InBasketPageComponent', () => {
  let component: InBasketPageComponent;
  let fixture: ComponentFixture<InBasketPageComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InBasketPageComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(InBasketPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render page header', () => {
    const header = fixture.nativeElement.querySelector('.page-header h1');
    expect(header).toBeTruthy();
  });

  it('should contain in-basket panel', () => {
    const panel = fixture.nativeElement.querySelector('app-in-basket-panel');
    expect(panel).toBeTruthy();
  });
});
