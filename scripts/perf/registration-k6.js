import { configuration, journey } from './go-live-journey.js';
const fixture = __ENV.FIXTURE_FILE ? open(__ENV.FIXTURE_FILE) : null;
export const options = configuration('registration');
export default function () { journey('registration', '/patients?page=0&size=20', '/patients', fixture); }
