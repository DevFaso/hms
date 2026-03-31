import { Component } from '@angular/core';

@Component({
  selector: 'app-privacy-policy',
  standalone: true,
  template: `
    <div class="privacy-policy">
      <div class="container">
        <h1>Privacy Policy</h1>
        <p class="last-updated">Last updated: March 30, 2026</p>

        <section>
          <h2>1. Introduction</h2>
          <p>
            Bitnest Technologies ("we", "us", or "our") operates the My Chart BF mobile application
            and the MediHub web platform (collectively, the "Service"). This Privacy Policy explains
            how we collect, use, disclose, and safeguard your personal and health information when
            you use our Service.
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
            The Service is intended for users aged 18 and older. We do not knowingly collect
            personal information from children under 18. If we become aware that we have collected
            data from a child under 18, we will take steps to delete that information.
          </p>
        </section>

        <section>
          <h2>9. Third-Party Services</h2>
          <p>
            The app uses the following third-party libraries for functionality purposes only (not
            for advertising or analytics tracking):
          </p>
          <ul>
            <li>Retrofit &amp; OkHttp &mdash; secure API communication</li>
            <li>AndroidX Biometric &mdash; biometric authentication</li>
            <li>AndroidX Security-Crypto &mdash; encrypted storage</li>
            <li>Coil &mdash; image loading</li>
            <li>Hilt &mdash; dependency injection</li>
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
            <li><strong>Website:</strong> https://hms.bitnesttechs.com</li>
          </ul>
        </section>
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
export class PrivacyPolicyComponent {}
