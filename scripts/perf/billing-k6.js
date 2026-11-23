import { configuration, journey } from './go-live-journey.js';
const fixture = __ENV.FIXTURE_FILE ? open(__ENV.FIXTURE_FILE) : null;
export const options = configuration('billing');
// Draft invoice creation only: never checkout, post, collect money, refund or settle insurance.
export default function () { journey('billing', `/billing-invoices/hospital/${__ENV.HOSPITAL_ID}?page=0&size=20`, '/billing-invoices', fixture); }
