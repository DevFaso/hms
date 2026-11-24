import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';

describe('D3704 channel labels describe the entire sender category', () => {
  for (const [language, catalog, all, appointments, pharmacy] of [
    ['en', en, 'all messages', 'Appointments', 'Pharmacy'],
    ['fr', fr, 'tous les messages', 'Rendez-vous', 'Pharmacie'],
    ['es', es, 'todos los mensajes', 'Citas', 'Farmacia'],
  ] as const) {
    it(`${language}: appointment choices include confirmations and follow-up, pharmacy choices include all notices`, () => {
      const labels = catalog.NOTIFICATIONS;
      expect(labels.TYPE_APPOINTMENT_REMINDER).toContain(appointments);
      expect(labels.TYPE_APPOINTMENT_REMINDER).toContain(all);
      expect(labels.TYPE_MEDICATION_REFILL).toContain(pharmacy);
      expect(labels.TYPE_MEDICATION_REFILL).toContain(all);
    });
  }
});
