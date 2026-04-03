import { Component } from '@angular/core';

@Component({
  selector: 'app-privacy-policy',
  standalone: true,
  template: `
    <div class="privacy-policy">
      <div class="lang-toggle">
        <button [class.active]="lang === 'en'" (click)="setLang('en')">English</button>
        <button [class.active]="lang === 'fr'" (click)="setLang('fr')">Français</button>
      </div>
      <div class="container">

        <!-- ═══ ENGLISH ═══ -->
        @if (lang === 'en') {
        <h1>Privacy Policy</h1>
        <p class="last-updated">Last updated: April 3, 2026</p>

        <section>
          <h2>1. Introduction</h2>
          <p>
            Bitnest Technologies ("we", "us", or "our") operates the MediHub Patient mobile
            application and the MediHub web platform (collectively, the "Service"). This Privacy
            Policy explains how we collect, use, disclose, and safeguard your personal and health
            information when you use our Service.
          </p>
          <p>
            By using the Service, you agree to the collection and use of information in accordance
            with this policy. If you do not agree, please do not use the Service.
          </p>
        </section>

        <section>
          <h2>2. Information We Collect</h2>

          <h3>2.1 Personal Information</h3>
          <ul>
            <li>Full name, date of birth, gender</li>
            <li>Email address and phone number</li>
            <li>Postal address and zip code</li>
            <li>Emergency contact information</li>
          </ul>

          <h3>2.2 Health &amp; Medical Information</h3>
          <ul>
            <li>Medical record number (MRN)</li>
            <li>Blood type, allergies, and medical history</li>
            <li>Appointments, encounters, and visit summaries</li>
            <li>Lab results, imaging reports, and vital signs</li>
            <li>Prescriptions and medication history</li>
            <li>Immunization records and treatment plans</li>
            <li>Referrals and consultation notes</li>
          </ul>

          <h3>2.3 Financial Information</h3>
          <ul>
            <li>Insurance provider and policy number</li>
            <li>Billing invoices and payment history</li>
          </ul>

          <h3>2.4 Device &amp; Usage Information</h3>
          <ul>
            <li>Device type, operating system, and app version</li>
            <li>Network connectivity status</li>
            <li>
              Biometric authentication status (fingerprint/face; biometric data is stored on-device
              only and never transmitted to our servers)
            </li>
          </ul>

          <h3>2.5 Communication Data</h3>
          <ul>
            <li>Messages exchanged with healthcare providers via the in-app chat</li>
            <li>Notification preferences and history</li>
          </ul>

          <h3>2.6 Documents &amp; Files</h3>
          <ul>
            <li>Photos and documents you upload (e.g., insurance cards, medical documents)</li>
          </ul>
        </section>

        <section>
          <h2>3. How We Use Your Information</h2>
          <p>We use the collected information to:</p>
          <ul>
            <li>Provide, operate, and maintain the Service</li>
            <li>Display your medical records, appointments, and health data</li>
            <li>Enable secure communication with your healthcare providers</li>
            <li>Process billing and insurance information</li>
            <li>Send appointment reminders and health-related notifications</li>
            <li>Authenticate your identity via biometric or password login</li>
            <li>Manage consent for inter-hospital record sharing</li>
            <li>Maintain audit logs of who accesses your medical records</li>
            <li>Improve and optimize the Service</li>
          </ul>
        </section>

        <section>
          <h2>4. Data Sharing &amp; Disclosure</h2>
          <p>We do not sell your personal or health information. We may share data with:</p>
          <ul>
            <li>
              <strong>Healthcare providers:</strong> Doctors, nurses, and staff involved in your
              care at participating hospitals
            </li>
            <li>
              <strong>Inter-hospital sharing:</strong> Only when you grant explicit consent through
              our consent management feature
            </li>
            <li>
              <strong>Service providers:</strong> Trusted third parties that assist in operating the
              Service (hosting, infrastructure), bound by confidentiality agreements
            </li>
            <li>
              <strong>Legal requirements:</strong> When required by law, regulation, or legal
              process
            </li>
          </ul>
        </section>

        <section>
          <h2>5. Data Security</h2>
          <p>We implement industry-standard security measures including:</p>
          <ul>
            <li>Encrypted data transmission (HTTPS/TLS)</li>
            <li>Encrypted local storage (AES-256-GCM) for authentication tokens</li>
            <li>Biometric authentication support</li>
            <li>Automatic session token refresh and expiration</li>
            <li>Role-based access control</li>
            <li>Comprehensive audit logging of record access</li>
          </ul>
          <p>
            While we strive to protect your information, no method of electronic transmission or
            storage is 100% secure. We cannot guarantee absolute security.
          </p>
        </section>

        <section>
          <h2>6. Data Retention</h2>
          <p>
            We retain your personal and health information for as long as your account is active or
            as needed to provide the Service. Medical records are retained in accordance with
            applicable healthcare regulations. You may request deletion of your account by
            contacting us.
          </p>
        </section>

        <section>
          <h2>7. Your Rights</h2>
          <p>Depending on your jurisdiction, you may have the right to:</p>
          <ul>
            <li>Access and review your personal data</li>
            <li>Correct inaccurate information</li>
            <li>Request deletion of your data</li>
            <li>Withdraw consent for data sharing</li>
            <li>View an audit log of who has accessed your records</li>
            <li>Export your health data</li>
          </ul>
        </section>

        <section>
          <h2>8. Children's Privacy</h2>
          <p>
            The Service is intended for users aged 17 and older. We do not knowingly collect
            personal information from children under 17. If we become aware that we have collected
            data from a child under 17, we will take steps to delete that information.
          </p>
        </section>

        <section>
          <h2>9. Third-Party Services</h2>
          <p>
            The app uses the following third-party libraries for functionality purposes only (not
            for advertising or analytics tracking):
          </p>
          <ul>
            <li>URLSession / Retrofit &amp; OkHttp &mdash; secure API communication</li>
            <li>iOS Keychain / AndroidX Security-Crypto &mdash; encrypted storage</li>
            <li>Face ID &amp; Touch ID / AndroidX Biometric &mdash; biometric authentication</li>
          </ul>
          <p>
            None of these libraries collect, transmit, or share personal data with third parties.
          </p>
        </section>

        <section>
          <h2>10. Changes to This Policy</h2>
          <p>
            We may update this Privacy Policy from time to time. We will notify you of any changes
            by updating the "Last updated" date at the top of this page. Continued use of the
            Service after changes constitutes acceptance of the updated policy.
          </p>
        </section>

        <section>
          <h2>11. Contact Us</h2>
          <p>If you have questions about this Privacy Policy, please contact us at:</p>
          <ul>
            <li><strong>Company:</strong> Bitnest Technologies</li>
            <li><strong>Email:</strong> privacy&#64;bitnesttechs.com</li>
            <li><strong>Website:</strong> https://bitnesttechs.com</li>
          </ul>
        </section>
        }

        <!-- ═══ FRANÇAIS ═══ -->
        @if (lang === 'fr') {
        <h1>Politique de Confidentialité</h1>
        <p class="last-updated">Dernière mise à jour : 3 avril 2026</p>

        <section>
          <h2>1. Introduction</h2>
          <p>
            Bitnest Technologies (« nous », « notre ») exploite l'application mobile MediHub
            Patient et la plateforme web MediHub (collectivement, le « Service »). Cette Politique
            de Confidentialité explique comment nous collectons, utilisons, divulguons et protégeons
            vos informations personnelles et de santé lorsque vous utilisez notre Service.
          </p>
          <p>
            En utilisant le Service, vous acceptez la collecte et l'utilisation des informations
            conformément à cette politique. Si vous n'êtes pas d'accord, veuillez ne pas utiliser le
            Service.
          </p>
        </section>

        <section>
          <h2>2. Informations que Nous Collectons</h2>

          <h3>2.1 Informations personnelles</h3>
          <ul>
            <li>Nom complet, date de naissance, genre</li>
            <li>Adresse e-mail et numéro de téléphone</li>
            <li>Adresse postale et code postal</li>
            <li>Coordonnées du contact d'urgence</li>
          </ul>

          <h3>2.2 Informations médicales et de santé</h3>
          <ul>
            <li>Numéro de dossier médical (NIP)</li>
            <li>Groupe sanguin, allergies et antécédents médicaux</li>
            <li>Rendez-vous, consultations et résumés de visites</li>
            <li>Résultats de laboratoire, rapports d'imagerie et signes vitaux</li>
            <li>Ordonnances et historique des médicaments</li>
            <li>Dossiers de vaccination et plans de traitement</li>
            <li>Références et notes de consultation</li>
          </ul>

          <h3>2.3 Informations financières</h3>
          <ul>
            <li>Assureur et numéro de police</li>
            <li>Factures et historique des paiements</li>
          </ul>

          <h3>2.4 Informations sur l'appareil et l'utilisation</h3>
          <ul>
            <li>Type d'appareil, système d'exploitation et version de l'application</li>
            <li>État de la connectivité réseau</li>
            <li>
              État de l'authentification biométrique (empreinte/visage ; les données biométriques
              sont stockées sur l'appareil uniquement et ne sont jamais transmises à nos serveurs)
            </li>
          </ul>

          <h3>2.5 Données de communication</h3>
          <ul>
            <li>Messages échangés avec les prestataires de soins via le chat intégré</li>
            <li>Préférences et historique des notifications</li>
          </ul>

          <h3>2.6 Documents et fichiers</h3>
          <ul>
            <li>Photos et documents que vous téléchargez (ex. : cartes d'assurance, documents médicaux)</li>
          </ul>
        </section>

        <section>
          <h2>3. Comment Nous Utilisons Vos Informations</h2>
          <p>Nous utilisons les informations collectées pour :</p>
          <ul>
            <li>Fournir, exploiter et maintenir le Service</li>
            <li>Afficher vos dossiers médicaux, rendez-vous et données de santé</li>
            <li>Permettre la communication sécurisée avec vos prestataires de soins</li>
            <li>Traiter les informations de facturation et d'assurance</li>
            <li>Envoyer des rappels de rendez-vous et des notifications de santé</li>
            <li>Authentifier votre identité via connexion biométrique ou par mot de passe</li>
            <li>Gérer le consentement pour le partage de dossiers inter-hospitalier</li>
            <li>Maintenir les journaux d'audit des accès à vos dossiers médicaux</li>
            <li>Améliorer et optimiser le Service</li>
          </ul>
        </section>

        <section>
          <h2>4. Partage et Divulgation des Données</h2>
          <p>Nous ne vendons pas vos informations personnelles ou de santé. Nous pouvons partager des données avec :</p>
          <ul>
            <li>
              <strong>Prestataires de soins :</strong> Médecins, infirmiers et personnel impliqués
              dans vos soins dans les hôpitaux participants
            </li>
            <li>
              <strong>Partage inter-hospitalier :</strong> Uniquement lorsque vous donnez votre
              consentement explicite via notre fonctionnalité de gestion du consentement
            </li>
            <li>
              <strong>Prestataires de services :</strong> Tiers de confiance qui assistent dans
              l'exploitation du Service (hébergement, infrastructure), liés par des accords de
              confidentialité
            </li>
            <li>
              <strong>Obligations légales :</strong> Lorsque requis par la loi, la réglementation
              ou une procédure judiciaire
            </li>
          </ul>
        </section>

        <section>
          <h2>5. Sécurité des Données</h2>
          <p>Nous mettons en œuvre des mesures de sécurité conformes aux normes de l'industrie :</p>
          <ul>
            <li>Transmission de données chiffrée (HTTPS/TLS)</li>
            <li>Stockage local chiffré (AES-256-GCM) pour les jetons d'authentification</li>
            <li>Support de l'authentification biométrique</li>
            <li>Rafraîchissement et expiration automatiques des jetons de session</li>
            <li>Contrôle d'accès basé sur les rôles</li>
            <li>Journalisation complète des audits d'accès aux dossiers</li>
          </ul>
          <p>
            Bien que nous nous efforcions de protéger vos informations, aucune méthode de
            transmission électronique ou de stockage n'est sécurisée à 100 %. Nous ne pouvons
            garantir une sécurité absolue.
          </p>
        </section>

        <section>
          <h2>6. Conservation des Données</h2>
          <p>
            Nous conservons vos informations personnelles et de santé tant que votre compte est
            actif ou aussi longtemps que nécessaire pour fournir le Service. Les dossiers médicaux
            sont conservés conformément aux réglementations de santé applicables. Vous pouvez
            demander la suppression de votre compte en nous contactant.
          </p>
        </section>

        <section>
          <h2>7. Vos Droits</h2>
          <p>Selon votre juridiction, vous pouvez avoir le droit de :</p>
          <ul>
            <li>Accéder à vos données personnelles et les consulter</li>
            <li>Corriger les informations inexactes</li>
            <li>Demander la suppression de vos données</li>
            <li>Retirer votre consentement au partage de données</li>
            <li>Consulter un journal d'audit des accès à vos dossiers</li>
            <li>Exporter vos données de santé</li>
          </ul>
        </section>

        <section>
          <h2>8. Vie Privée des Enfants</h2>
          <p>
            Le Service est destiné aux utilisateurs âgés de 17 ans et plus. Nous ne collectons pas
            sciemment d'informations personnelles auprès d'enfants de moins de 17 ans. Si nous
            apprenons que nous avons collecté des données d'un enfant de moins de 17 ans, nous
            prendrons des mesures pour supprimer ces informations.
          </p>
        </section>

        <section>
          <h2>9. Services Tiers</h2>
          <p>
            L'application utilise les bibliothèques tierces suivantes à des fins de fonctionnalité
            uniquement (pas pour la publicité ni le suivi analytique) :
          </p>
          <ul>
            <li>URLSession / Retrofit &amp; OkHttp &mdash; communication API sécurisée</li>
            <li>Trousseau iOS / AndroidX Security-Crypto &mdash; stockage chiffré</li>
            <li>Face ID &amp; Touch ID / AndroidX Biometric &mdash; authentification biométrique</li>
          </ul>
          <p>
            Aucune de ces bibliothèques ne collecte, ne transmet ni ne partage de données
            personnelles avec des tiers.
          </p>
        </section>

        <section>
          <h2>10. Modifications de cette Politique</h2>
          <p>
            Nous pouvons mettre à jour cette Politique de Confidentialité de temps à autre. Nous
            vous informerons de tout changement en mettant à jour la date de « Dernière mise à
            jour » en haut de cette page. L'utilisation continue du Service après les modifications
            constitue une acceptation de la politique mise à jour.
          </p>
        </section>

        <section>
          <h2>11. Nous Contacter</h2>
          <p>Si vous avez des questions sur cette Politique de Confidentialité, contactez-nous :</p>
          <ul>
            <li><strong>Société :</strong> Bitnest Technologies</li>
            <li><strong>E-mail :</strong> privacy&#64;bitnesttechs.com</li>
            <li><strong>Site web :</strong> https://bitnesttechs.com</li>
          </ul>
        </section>
        }

      </div>
    </div>
  `,
  styles: [
    `
      .privacy-policy {
        max-width: 800px;
        margin: 0 auto;
        padding: 40px 20px;
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        color: #333;
        line-height: 1.7;
      }
      .lang-toggle {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
        margin-bottom: 8px;
      }
      .lang-toggle button {
        padding: 6px 16px;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        background: #f9fafb;
        color: #6b7280;
        font-size: 0.85rem;
        cursor: pointer;
        transition: all 0.2s;
      }
      .lang-toggle button.active {
        background: #0085CA;
        color: #fff;
        border-color: #0085CA;
      }
      h1 {
        font-size: 2rem;
        margin-bottom: 4px;
        color: #1a1a2e;
      }
      .last-updated {
        color: #666;
        font-size: 0.9rem;
        margin-bottom: 32px;
      }
      h2 {
        font-size: 1.3rem;
        margin-top: 32px;
        margin-bottom: 12px;
        color: #1a1a2e;
      }
      h3 {
        font-size: 1.1rem;
        margin-top: 16px;
        margin-bottom: 8px;
        color: #444;
      }
      ul {
        padding-left: 24px;
      }
      li {
        margin-bottom: 6px;
      }
      section {
        margin-bottom: 16px;
      }
    `,
  ],
})
export class PrivacyPolicyComponent {
  lang: 'en' | 'fr' = 'en';

  constructor() {
    // Auto-detect browser language
    const browserLang = (navigator.language || '').toLowerCase();
    this.lang = browserLang.startsWith('fr') ? 'fr' : 'en';
  }

  setLang(lang: 'en' | 'fr') {
    this.lang = lang;
  }
}
