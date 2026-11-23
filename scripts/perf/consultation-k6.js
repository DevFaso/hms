import { configuration, journey } from './go-live-journey.js';
const fixture = __ENV.FIXTURE_FILE ? open(__ENV.FIXTURE_FILE) : null;
export const options = configuration('consultation');
export default function () { journey('consultation', '/encounters?page=0&size=20', '/encounters', fixture); }
