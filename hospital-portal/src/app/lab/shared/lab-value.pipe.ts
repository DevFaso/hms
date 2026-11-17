/**
 * The lab screens' import path for the shared locale number formatter
 * (shared/pipes/lab-value.pipe.ts, QA D1002 / D1106): one implementation,
 * kept importable from here so the lab feature does not reach across.
 */
export {
  LabValuePipe,
  LabRangePipe,
  LocaleNumberPipe,
  formatLabNumber,
} from '../../shared/pipes/lab-value.pipe';
