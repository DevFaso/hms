# Pharmacy Module — User Guide & Runbook (French-first)

> Guide synthétique pour le personnel pharmacie et les opérateurs HMS.
> Document vivant — la source de vérité reste `docs/pharmacy-implementation-plan.md`.

## 1. Rôles et permissions (résumé)

| Rôle | Peut faire |
|---|---|
| `PHARMACIST` | Dispenser, gérer files, imprimer reçus, valider paiements |
| `PHARMACY_INVENTORY_CLERK` | Réception, ajustements stock, inventaire |
| `PHARMACY_STORE_MANAGER` | Tout du clerc + seuils de réapprovisionnement, rapports expiration |
| `BILLING_SPECIALIST` | Paiements, factures, réconciliation |
| `CLAIMS_REVIEWER` | Créer/soumettre/accepter/rejeter/payer les claims, exports CSV et FHIR |
| `HOSPITAL_ADMIN` | Registre pharmacie, catalogue médicaments, supervision |
| `PATIENT` | Portail : factures, notifications, historique médicaments |

## 2. Parcours "jour typique"

### 2.1 Matin — pharmacien hospitalier (Tier 1)
1. `/pharmacy/dispensing` — consulter la file d'attente des ordonnances.
2. Pour chaque ordonnance :
   - Si stock suffisant → dispenser, le stock est décrémenté automatiquement.
   - Si rupture partielle → dispenser ce qui est disponible, router le reste via `/pharmacy/stock-routing`.
   - Si rupture totale → trois options : partenaire (Tier 2), impression patient (Tier 3), back-order.
3. `/pharmacy/checkout` — encaisser (espèces ou mobile money), imprimer le reçu FR.

### 2.2 Soir — responsable pharmacie
1. `/pharmacy/inventory` — revoir alertes de réapprovisionnement.
2. `/pharmacy/stock-adjustment` — saisir pertes, retours, casses (motif obligatoire).
3. `/pharmacy/claims` — revue quotidienne des claims AMU et export CSV.

### 2.3 Patient (portail)
- Factures pharmacie, historique paiements, historique médicaments, notifications SMS.

## 3. Notifications SMS (toutes en français)

| Événement | Destinataire | Déclencheur |
|---|---|---|
| Prêt pour retrait | Patient | Dispense créée |
| Rupture de stock | Patient | Routage externe ou back-order |
| Rappel de renouvellement | Patient | Programmé selon jours-de-stock |
| Offre d'ordonnance | Pharmacie partenaire | `routeToPartner` |
| Rappel (2h) | Pharmacie partenaire | Planificateur (pas de réponse) |
| Refus auto (4h) | Pharmacie partenaire | Planificateur |
| Accepté par partenaire | Patient | Réponse `1` du partenaire |
| Délivré par partenaire | Patient | Réponse `3` du partenaire |

## 4. Canal SMS partenaire (Phase 7a)

- Endpoint inbound : `POST /webhooks/partner-sms`
- En-tête requis : `X-HMS-Partner-Signature: <shared-secret>` (fail-closed si non configuré)
- Corps accepté : `{ "from": "+226...", "body": "1 ABC12" }`
- Codes de réponse :
  - `1 <ref>` → accepter
  - `2 <ref>` → refuser
  - `3 <ref>` → confirmer délivrance
  - Fallback flou FR : `oui` / `non` / `délivré`
- Planificateur : relance à 2h, refus auto à 4h (configurable via `pharmacy.partner.scheduler.*`).

## 5. Claims AMU (Phase 6)

- Cycle : `DRAFT → SUBMITTED → ACCEPTED → PAID` (ou `REJECTED`).
- Tenant-scoped : chaque requête est validée contre l'hôpital actif de l'utilisateur.
- Export CSV : `GET /pharmacy/claims/export/csv?status=SUBMITTED&status=ACCEPTED`
- Export FHIR : `GET /pharmacy/claims/export/fhir?status=SUBMITTED&status=ACCEPTED`
- Tous les changements de statut émettent un événement d'audit `CLAIM_SUBMITTED`.

## 6. Couverture audit (T-69)

Les événements suivants sont émis pour la pharmacie (enum `AuditEventType`) :

| Événement | Source |
|---|---|
| `STOCK_RECEIPT`, `STOCK_ADJUSTMENT`, `STOCK_TRANSFER`, `STOCK_RETURN`, `STOCK_REORDER_ALERT` | Services inventaire |
| `DISPENSE_CREATED`, `DISPENSE_CANCELLED` | `DispenseService` |
| `PRESCRIPTION_ROUTED_EXTERNAL`, `PRESCRIPTION_SENT_TO_PARTNER`, `PRESCRIPTION_PRINTED`, `PRESCRIPTION_BACKORDER` | `StockOutRoutingService` |
| `CLAIM_SUBMITTED` | `PharmacyClaimService` (toutes transitions) |
| `PRESCRIPTION_CREATED/UPDATED/DISCONTINUED` | `PrescriptionService` |

## 7. Sécurité — revue synthétique (T-70)

- **Tenant isolation** : `RoleValidator.requireActiveHospitalId()` dans chaque service pharmacie.
- **RBAC** : `@PreAuthorize` sur tous les contrôleurs pharmacy + contraintes dans `SecurityConfig`.
- **Webhook partenaire** : en-tête secret partagé, CSRF-exempt, `permitAll` pour JWT stateless.
- **PHI** : aucun nom complet de patient dans les SMS partenaires (initiales uniquement).
- **Audit** : tous les événements critiques sont tracés avec `userId` et `resourceId`.
- **Secrets** : `pharmacy.partner.webhook.secret`, Twilio, mobile money → jamais en dur, lus via `@Value`.

## 8. Limitations MVP connues

- Pas de queue offline côté pharmacien (T-68) — repli papier documenté.
- Pas de signature dual pour substances contrôlées (Phase v2).
- Pas de FHIR exposé côté partenaires externes (Phase 7c).
- Perf tests stockage/dispense non automatisés (Phase v2).

## 9. Configuration clé

| Propriété | Défaut | Description |
|---|---|---|
| `pharmacy.partner.webhook.secret` | (vide → 401) | Secret pour le webhook SMS partenaire |
| `pharmacy.partner.scheduler.interval-ms` | `900000` (15 min) | Fréquence du planificateur de relance |
| `pharmacy.partner.scheduler.initial-delay-ms` | `60000` | Délai initial au démarrage |
