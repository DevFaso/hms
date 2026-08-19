---
marp: true
theme: default
size: 16:9
paginate: true
header: 'MediHub — Burkina Faso'
footer: 'Bitnest Technologies — Mai 2026'
style: |
  section { font-size: 0.78em; line-height: 1.35; padding: 50px 60px; }
  section h1 { color: #1e40af; font-size: 1.6em; }
  section h2 { color: #1e40af; border-bottom: 2px solid #cbd5e1; padding-bottom: 0.2em; }
  section h3 { color: #475569; margin-top: 0.8em; }
  table { font-size: 0.78em; border-collapse: collapse; }
  table th { background: #1e40af; color: white; padding: 0.4em 0.6em; }
  table td { padding: 0.3em 0.6em; border-bottom: 1px solid #e2e8f0; }
  blockquote { border-left: 4px solid #2563eb; padding-left: 1em; color: #475569; font-style: italic; }
  code { background: #f1f5f9; padding: 0.1em 0.3em; border-radius: 3px; font-size: 0.9em; }
  pre { font-size: 0.65em; background: #1e293b; color: #f8fafc; padding: 1em; border-radius: 6px; }
  pre code { background: transparent; color: inherit; }
  ul, ol { line-height: 1.5; }
  hr { border: none; border-top: 1px solid #e2e8f0; }
---

# MediHub — Présentation au Ministère de la Santé du Burkina Faso

> **Date du document :** 13 mai 2026
> **Préparé par :** Bitnest Technologies — équipe MediHub
> **Public cible :** Ministère de la Santé et de l'Hygiène Publique du Burkina Faso, ANPTIC, Direction de l'Informatique et de la Statistique
> **Format :** Markdown structuré en diapositives (séparées par `---`). Convertible en PowerPoint, Keynote, Google Slides ou PDF via Marp / Pandoc / VSCode-Markdown-Slides.
>
> **Note sur l'usage de ce document :** Ce support a été rédigé pour être **honnête et défendable lors d'un audit technique**. Les fonctionnalités présentées comme « livrées » correspondent à du code en production dans nos environnements de développement et de pré-production. Les éléments listés en « feuille de route » sont signalés comme tels — ne pas les présenter comme déjà disponibles.

---

## Diapositive 1 — Couverture

### MediHub

**Une plateforme intégrée de gestion hospitalière pour le Burkina Faso**

Souveraineté des données • Sécurité de niveau bancaire • Interopérabilité

*Présenté par Bitnest Technologies*
*Mai 2026*

---

## Diapositive 2 — Sommaire

1. Le défi du système de santé burkinabè
2. Notre vision
3. Présentation de la solution MediHub
4. Architecture technique
5. Fonctionnalités déjà opérationnelles
6. Sécurité, conformité et souveraineté des données
7. Pourquoi MediHub plutôt qu'une alternative ?
8. Le contexte international du numérique en santé
9. Bénéfices attendus pour le Burkina Faso
10. Plan de déploiement par phases
11. Modèle économique et tarification
12. Mesures de succès et indicateurs
13. Conditions de partenariat
14. Prochaines étapes

---

## Diapositive 3 — Le défi : le système de santé burkinabè en chiffres

Le Burkina Faso fait face à des défis structurels que la digitalisation peut atténuer :

- **Population de ~22 millions d'habitants** répartis sur 13 régions sanitaires, avec une densité médicale parmi les plus faibles d'Afrique de l'Ouest (environ 1 médecin pour 17 000 habitants selon les données OMS récentes).
- **Dossiers patients principalement papier** dans la majorité des centres de santé périphériques, entraînant pertes d'informations, doublons, et difficulté de suivi des patients d'un centre à l'autre.
- **Référencement inter-établissements limité** — un patient suivi à un CSPS qui doit être évacué vers un CMA ou un CHR perd souvent son historique en cours de route.
- **Surveillance épidémiologique tardive** — les remontées vers la DGISS et le ministère se font sur cycles longs, retardant les réponses aux flambées (méningite, dengue, paludisme grave, etc.).
- **Pression budgétaire** — chaque franc CFA dépensé doit générer un retour démontrable sur la santé publique et l'efficience administrative.

> **Notes pour l'orateur :** Citer ici la Stratégie Nationale de Santé Numérique 2018-2025 du Burkina Faso, et la rappeler comme cadre de référence dans lequel MediHub s'inscrit, pas en concurrence.

---

## Diapositive 4 — Notre vision

> **Une plateforme nationale de santé numérique, hébergée souverainement, qui transforme chaque consultation en donnée structurée, chaque référencement en transition fluide, et chaque flambée épidémique en signal détectable en temps réel.**

Trois principes directeurs :

1. **Souveraineté avant tout** — données de santé hébergées sur infrastructure choisie par l'État burkinabè, jamais sortantes du territoire sans accord explicite.
2. **Adoption progressive** — déploiement par hôpital, par région, sans interrompre le fonctionnement existant. Cohabitation avec le papier pendant la phase de transition.
3. **Interopérabilité standard** — formats HL7 FHIR, API ouvertes, exports DHIS2-compatibles. Pas de verrouillage propriétaire.

---

## Diapositive 5 — Qu'est-ce que MediHub ?

MediHub est une **plateforme intégrée de gestion hospitalière (HMS)** conçue pour les systèmes de santé multi-établissements.

| Composant | Description | Public |
|---|---|---|
| **Portail web** | Application Angular pour le personnel soignant et administratif (médecins, infirmiers, réceptionnistes, gestionnaires). | Hôpitaux, CMA, CSPS |
| **Application Android** | Application native pour les patients : prise de rendez-vous, consultation des résultats, rappels de traitement. | Patients citoyens |
| **Application iOS** | Équivalent natif iOS de l'application Android. | Patients citoyens (segment iPhone) |
| **API backend** | Cœur applicatif Spring Boot exposant des API REST sécurisées. Permet l'intégration avec systèmes tiers (CARFO, CNSS, LIMS de laboratoire, DHIS2). | Intégrateurs, partenaires |

Architecture **multi-hôpital nativement** : un seul déploiement central peut servir tous les établissements de santé du pays, chaque établissement voyant uniquement ses propres données (cloisonnement strict via attribut `hospital_id`).

---

## Diapositive 6 — Architecture technique (vue d'ensemble)

```text
                    ┌─────────────────────────────────────┐
                    │   PATIENTS                          │
                    │   (Android / iOS / Web)             │
                    └──────────────┬──────────────────────┘
                                   │  HTTPS + OAuth2/OIDC
                                   ▼
                    ┌─────────────────────────────────────┐
                    │   PERSONNEL SOIGNANT                │
                    │   (Portail Angular)                 │
                    └──────────────┬──────────────────────┘
                                   │  HTTPS + JWT
                                   ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │                  API BACKEND (Spring Boot)                       │
   │  • RBAC multi-hôpital  • Audit log  • Chiffrement PHI            │
   │  • Validation FHIR     • Rate limiting  • Observabilité          │
   └─────┬──────────────────┬──────────────────┬──────────────────────┘
         │                  │                  │
         ▼                  ▼                  ▼
   ┌──────────┐      ┌────────────┐     ┌─────────────────┐
   │ PostgreSQL│      │  Keycloak  │     │ Intégrations    │
   │  (PHI     │      │ (SSO/OIDC, │     │ tierces : DHIS2,│
   │  chiffrée)│      │   MFA)     │     │ CARFO, LIMS…    │
   └──────────┘      └────────────┘     └─────────────────┘
```

Principes d'architecture :

- **Stateless backend** : montée en charge horizontale par simple ajout de nœuds. Aucun état persistant en mémoire d'instance.
- **Séparation stricte PHI / authentification** : la base patient est distincte de la base d'authentification Keycloak.
- **Cloud-agnostic** : déployable sur Railway, AWS, Azure, GCP, ou serveurs souverains nationaux (datacenter ANPTIC, partenariat hébergement local).

---

## Diapositive 7 — Stack technologique

Choix techniques **éprouvés en milieu hospitalier mondial** — pas d'expérimentation sur des technologies marginales.

| Couche | Technologie | Pourquoi ce choix |
|---|---|---|
| **Backend** | Spring Boot 3.x (Java 21) | Standard mondial pour les SI hospitaliers (Epic, Cerner, Allscripts). Écosystème mature, sécurité auditée, support à long terme. |
| **Frontend web** | Angular 18 (TypeScript) | Architecture en composants, accessibilité WCAG, support du français natif, montée en compétence facile pour développeurs locaux. |
| **Mobile** | Android natif (Kotlin) + iOS natif (Swift) | Performance maximale, accès aux capteurs (NFC pour cartes d'identité, caméra pour codes-barres médicaments). |
| **Base de données** | PostgreSQL 16 | Open source, ACID, robustesse prouvée à des dizaines de millions de patients (déployée par des systèmes nationaux comme l'Estonie). |
| **Authentification** | Keycloak 26 | Standard ouvert OAuth2/OIDC, MFA via TOTP, fédération SAML possible avec systèmes existants (carte d'identité numérique nationale future). |
| **Conteneurisation** | Docker + Kubernetes-ready | Déploiement reproductible, isolation des services, indépendance vis-à-vis du fournisseur cloud. |
| **CI/CD** | GitHub Actions, Sonar, JaCoCo | Tests automatisés à chaque modification, couverture de code > 80% requise, analyse de qualité bloquante avant fusion. |

---

## Diapositive 8 — Fonctionnalités déjà opérationnelles (1/3) : Gestion des patients

Modules **livrés et fonctionnant en environnement de pré-production** :

- ✅ **Création et recherche de dossier patient** — par nom, numéro d'identité, date de naissance, ou numéro de téléphone. Déduplication automatique.
- ✅ **Démographie complète** — nom, sexe, date de naissance, contact d'urgence, adresse géolocalisée, ethnie/langue préférée, statut matrimonial, profession.
- ✅ **Historique médical structuré** — antécédents personnels, antécédents familiaux, allergies, vaccinations, traitements de fond.
- ✅ **Multi-hôpital strict** — un patient suivi au CHU Yalgado peut être référencé à un CMA de Bobo-Dioulasso ; les soignants des deux établissements voient son dossier mais aucun établissement tiers n'y a accès sans référencement explicite.
- ✅ **Gestion des consentements** — chaque accès au dossier nécessite un motif tracé. Le patient peut consulter qui a accédé à son dossier (transparence inspirée du RGPD européen).

---

## Diapositive 9 — Fonctionnalités déjà opérationnelles (2/3) : Workflow clinique

- ✅ **Prise de rendez-vous** — par le portail (personnel) ou l'application mobile (patient). Gestion des disponibilités par soignant, par salle, par équipement (échographe, scanner).
- ✅ **File d'attente numérique** — affichage temps réel des patients en attente, priorisation triage (urgences vitales en premier), estimation du temps d'attente.
- ✅ **Consultation médicale** — saisie structurée des motifs, examens cliniques, diagnostics (CIM-11), prescriptions.
- ✅ **Prescription électronique** — médicaments avec posologie, durée, interactions vérifiées automatiquement. Export PDF imprimable pour pharmacie.
- ✅ **Demandes d'examens complémentaires** — laboratoire, imagerie, anatomopathologie. Suivi du statut de la demande.
- ✅ **Référencement inter-établissements** — transfert du dossier patient avec motif clinique et niveau d'urgence.
- ✅ **Notes de suite** — chronologie horodatée et signée numériquement de tous les actes posés.

---

## Diapositive 10 — Fonctionnalités déjà opérationnelles (3/3) : Administration

- ✅ **Gestion fine des rôles et permissions** — 26 rôles métier prédéfinis (médecin, infirmier, sage-femme, technicien laboratoire, pharmacien, réceptionniste, administrateur, super-administrateur, etc.) avec permissions granulaires sur chaque action.
- ✅ **Authentification unique (SSO)** via Keycloak — un seul identifiant pour tous les modules. Possibilité de fédération avec un système d'identité national futur.
- ✅ **Authentification à deux facteurs (MFA via TOTP)** — obligatoire pour les rôles à privilèges élevés.
- ✅ **Journalisation d'audit complète** — chaque accès, modification, et action sensible est tracée avec utilisateur, horodatage, adresse IP, et motif.
- ✅ **Tableaux de bord opérationnels** — nombre de consultations par jour, taux d'occupation des lits, durée moyenne d'attente, file d'attente en temps réel.
- ✅ **Export de données** — formats CSV, JSON, et FHIR-compatible pour interopérabilité avec DHIS2 et systèmes statistiques nationaux.
- ✅ **Support multi-environnement** — séparation stricte développement / pré-production / production avec promotion contrôlée des versions.

> **Notes pour l'orateur :** Insister sur le « 26 rôles prédéfinis » — c'est un signe de maturité et d'adaptation aux réalités hospitalières, pas une démo générique.

---

## Diapositive 11 — Sécurité : les fondations

MediHub a été conçu **pour la santé**, où une fuite de données équivaut à une crise de confiance nationale.

### Couche réseau

- **HTTPS strict (TLS 1.3)** sur tous les points d'accès. Aucune communication en clair, jamais.
- **HSTS** activé pour empêcher les attaques par dégradation de protocole.
- **CSP, X-Frame-Options, X-Content-Type-Options** configurés pour bloquer le clickjacking et le sniffing MIME.

### Authentification

- **OAuth2 / OpenID Connect** via Keycloak — standard de l'industrie utilisé par les banques, Google Workspace, Microsoft 365.
- **MFA via TOTP** (compatible Google Authenticator, FreeOTP, Microsoft Authenticator) — obligatoire pour les comptes administrateurs et médecins prescripteurs.
- **Politique de mot de passe configurable** — longueur minimale, complexité, expiration, blocage après tentatives.

### Données au repos

- **Chiffrement PostgreSQL natif** sur les colonnes sensibles (PHI : données de santé personnelles identifiables). Algorithme AES-256.
- **Sauvegardes chiffrées et géo-redondantes** avec rétention configurable (30 jours actifs, 1 an archivés par défaut).

### Données en transit

- **TLS 1.3** entre tous les composants, y compris en interne.
- **Authentification mutuelle** (mTLS) possible pour les intégrations tierces sensibles.

---

## Diapositive 12 — Sécurité : contrôle d'accès

### Modèle RBAC multi-hôpital

Chaque utilisateur possède :

- Un ou plusieurs **rôles métier** (`ROLE_DOCTOR`, `ROLE_NURSE`, `ROLE_PHARMACIST`, etc.)
- Une ou plusieurs **affectations hospitalières** (peut travailler au CMA de Ouahigouya ET au CHR de Kaya)
- Des **permissions calculées** au moment de chaque requête : un médecin ne voit que les patients de SES établissements affectés.

### Cloisonnement automatique

Toute requête à l'API est filtrée :

```sql
-- Exemple : recherche de patients
SELECT * FROM patients
WHERE hospital_id IN (<liste des hôpitaux affectés à l'utilisateur courant>)
```

**Aucun moyen pour un médecin du CHU de Bobo de voir, par accident ou intentionnellement, le dossier d'un patient suivi exclusivement à Ouagadougou.**

### Délégation et révocation

- L'attribution d'un rôle est tracée : qui l'a accordé, quand, pour quel motif.
- La révocation est immédiate — un employé licencié perd l'accès en moins de 5 minutes (durée de vie maximale d'un jeton).

---

## Diapositive 13 — Conformité et souveraineté des données

### Conformité réglementaire

Le système est conçu pour répondre aux exigences :

- **Loi nº 010-2004/AN** (protection des données personnelles au Burkina Faso) — droit d'accès, de rectification, de suppression, de portabilité.
- **CIL** (Commission de l'Informatique et des Libertés du Burkina Faso) — déclaration et autorisation préalables prises en charge dans la phase de déploiement.
- **Loi nº 045-2009/AN** (transactions électroniques) — signatures électroniques sur les ordonnances et les comptes-rendus.
- **Recommandations OMS** sur la santé numérique (Global Strategy on Digital Health 2020-2025).

### Souveraineté des données — engagement ferme

| Principe | Engagement contractuel |
|---|---|
| **Hébergement** | Sur infrastructure désignée par l'État burkinabè. Premier choix : datacenter ANPTIC ou opérateur national agréé. Deuxième choix : opérateur cloud africain (Liquid Cloud, MainOne, etc.). |
| **Propriété des données** | 100% propriété de l'État burkinabè. Bitnest n'est qu'opérateur technique. |
| **Sortie du pays** | Aucun transfert hors Burkina Faso sans autorisation explicite et tracée du Ministère. |
| **Réversibilité** | À tout moment, l'État peut récupérer l'intégralité des données dans un format ouvert documenté (FHIR + dump PostgreSQL). |
| **Audit indépendant** | Droit de l'État de mandater à tout moment un auditeur indépendant pour vérifier le respect de ces engagements. |

---

## Diapositive 14 — Applications mobiles : pensées pour le contexte africain

### Application Patient (Android + iOS)

Fonctionnalités déjà livrées :

- Prise de rendez-vous en ligne avec son médecin habituel ou un nouveau soignant.
- Consultation des résultats d'examens dès qu'ils sont validés.
- Rappels de traitement et de vaccinations.
- Carnet de santé personnel téléchargeable hors ligne.
- Notifications de campagnes de santé publique (vaccinations, dépistages).

### Pensée pour la réalité du terrain burkinabè

- ✅ **Optimisation faible bande passante** — fonctionne sur 2G/3G, taille d'application < 15 Mo.
- 🛣️ **Mode hors ligne** (en feuille de route Phase 2) — consultation du carnet de santé et prise de rendez-vous sans connexion, synchronisation différée.
- 🛣️ **Support des langues nationales** (en feuille de route) — Mooré, Dioula, Fulfuldé en plus du français.
- ✅ **Compatibilité smartphones d'entrée de gamme** — Android 6+ supporté (téléphones à partir de 30 000 F CFA).

---

## Diapositive 15 — État réel du projet (transparence)

Pour mériter votre confiance, voici **honnêtement** où nous en sommes :

### ✅ En production technique (testé, déployé en pré-production)

- Backend Spring Boot avec API REST sécurisée
- Portail Angular fonctionnel (gestion patients, RDV, prescriptions, RBAC multi-hôpital)
- Applications Android et iOS (fonctionnalités patient principales)
- Infrastructure CI/CD complète avec tests automatisés
- Chiffrement, audit, sauvegardes opérationnels
- Déploiement multi-environnement (dev, pré-production, production)

### 🔄 En cours d'intégration (semaines/mois à venir)

- Migration finale vers SSO Keycloak en production
- Internationalisation française complète (la base existe, les libellés sont en cours de traduction)
- Module de facturation / assurance (intégration CARFO et CNSS prévue Phase 2)
- Intégrations LIMS pour automatisation labos

### 🛣️ En feuille de route (Phases 2-3, fonction des partenariats)

- Mode hors ligne mobile pour zones à connectivité intermittente
- Langues nationales (Mooré, Dioula, Fulfuldé)
- Module de surveillance épidémiologique avec remontée automatique vers DGISS
- Télémédecine (visioconférence intégrée)
- Intelligence artificielle d'aide au diagnostic (en partenariat avec institutions académiques)

> **Notes pour l'orateur :** Cette honnêteté est notre meilleur atout vs. concurrents qui survendent. Le ministère a déjà été échaudé par des projets sur-promis ; notre transparence est un différentiateur.

---

## Diapositive 16 — Comparaison avec les alternatives

| Critère | MediHub | DHIS2 | OpenMRS | Solutions propriétaires (Cerner, Epic) |
|---|---|---|---|---|
| **Coût de licence** | Modèle abonnement transparent | Gratuit (open source) | Gratuit (open source) | Très élevé (millions USD) |
| **Adapté au contexte africain** | Conçu pour | Largement déployé en Afrique | Déployé en Afrique de l'Est notamment | Conçu pour les hôpitaux occidentaux |
| **Multi-hôpital natif** | ✅ Oui | ⚠️ Possible mais complexe à configurer | ⚠️ Possible | ✅ Oui mais coûteux |
| **Application mobile patient** | ✅ Android + iOS natifs | ❌ Tableau de bord web seulement | ⚠️ Apps tierces | ✅ Mais fermées |
| **Souveraineté des données** | ✅ Engagement contractuel ferme | ✅ Auto-hébergeable | ✅ Auto-hébergeable | ❌ Souvent cloud du fournisseur |
| **Standards interopérabilité (FHIR)** | ✅ Oui | ⚠️ Partiellement | ✅ Oui | ✅ Oui |
| **Personnalisation** | ✅ Adaptable au contexte burkinabè | ⚠️ Modèle de données rigide | ✅ Mais nécessite expertise pointue | ❌ Verrouillage propriétaire |
| **Support local francophone** | ✅ Équipe basée en région | ⚠️ Communauté principalement anglophone | ⚠️ Idem | ❌ Anglais uniquement |
| **Délai de mise en service** | 6-12 mois | 12-24 mois | 18-36 mois | 24-48 mois |

### Notre positionnement

MediHub n'est **pas** un remplacement de DHIS2 — DHIS2 est excellent pour la statistique épidémiologique agrégée et restera le système de référence du ministère pour ses indicateurs nationaux. **MediHub est complémentaire** : il gère le quotidien clinique des hôpitaux et alimente DHIS2 en données structurées, automatiquement.

---

## Diapositive 17 — Le marché mondial du numérique en santé (contexte)

### Tendances globales

- L'**OMS** a publié en 2020 sa *Global Strategy on Digital Health 2020-2025*, exhortant les États membres à intégrer le numérique dans leurs systèmes de santé.
- Le marché mondial des HMS est en **croissance soutenue à deux chiffres** depuis 2020, accélérée par la COVID-19 qui a démontré l'importance des données de santé en temps réel.
- L'**Afrique CDC** rapporte (2022) que **21 pays africains** disposent désormais d'une stratégie nationale de santé numérique.
- Le Burkina Faso fait partie de ces pays — la **Stratégie Nationale de Santé Numérique 2018-2025** existe et MediHub s'inscrit dans son cadre.

### Précédents africains à étudier

- **Rwanda** : digitalisation accélérée du système de santé via la plateforme nationale, citée comme modèle continental.
- **Kenya** : déploiement national de DHIS2 + intégrations cliniques au niveau hospitalier.
- **Sénégal** : projet *e-santé* incluant dossier patient national.
- **Maroc** : déploiement d'un système national de gestion hospitalière.

> **Notes pour l'orateur :** Ne pas inventer de chiffres. Si on vous demande « combien de pays utilisent MediHub », répondez honnêtement : « MediHub est en pré-production opérationnelle ; le Burkina Faso aurait l'opportunité d'être le premier déploiement national, avec les avantages stratégiques et tarifaires qui en découlent. »

---

## Diapositive 18 — Bénéfices attendus pour le Burkina Faso (1/2)

### Pour les patients

- **Continuité des soins** — son dossier le suit d'un établissement à l'autre.
- **Moins de temps perdu** — RDV en ligne, file d'attente connue à l'avance.
- **Accès à ses propres données** — l'application mobile lui montre ses résultats, ses ordonnances, son historique.
- **Confiance** — il sait qui a consulté son dossier et pour quel motif.

### Pour les soignants

- **Moins de tâches administratives** — fini les triples saisies, le report manuel sur registres papier.
- **Décisions cliniques mieux informées** — historique complet du patient à portée de clic.
- **Sécurité des prescriptions** — interactions médicamenteuses signalées automatiquement.
- **Continuité d'équipe** — un médecin de garde reprend le dossier d'un confrère sans intermédiaire.

### Pour les administrateurs hospitaliers

- **Pilotage temps réel** — taux d'occupation, consommation médicaments, files d'attente, productivité par service.
- **Détection de fraude** — anomalies de facturation, prescriptions atypiques, consommables détournés.
- **Gestion RH** — temps de présence, productivité, plannings.

---

## Diapositive 19 — Bénéfices attendus pour le Burkina Faso (2/2)

### Pour le Ministère de la Santé

- **Visibilité nationale** sur l'activité hospitalière en temps réel — combien de consultations aujourd'hui à Bobo-Dioulasso, à Ouahigouya, à Fada N'Gourma ?
- **Surveillance épidémiologique accélérée** — détection de signaux d'alerte (cluster de cas suspects) avant qu'une flambée ne devienne une épidémie.
- **Meilleure planification budgétaire** — données réelles d'utilisation des médicaments, équipements, personnels.
- **Conformité aux engagements internationaux** — reporting OMS, UNICEF, financement mondial Fonds mondial / GAVI facilité.

### Pour l'État burkinabè (vision long-terme)

- **Souveraineté numérique en santé** — le pays maîtrise son patrimoine de données de santé.
- **Création d'emplois technologiques** — formation et recrutement local pour le déploiement, la maintenance, l'évolution.
- **Capacité d'export** — un Burkina Faso pionnier d'une plateforme de santé numérique éprouvée peut la proposer à des pays voisins (Mali, Niger, Côte d'Ivoire) — création de valeur économique.
- **Renforcement de la résilience face aux crises sanitaires** — la prochaine COVID, Ebola, ou flambée méningée trouvera un pays mieux armé.

---

## Diapositive 20 — Plan de déploiement par phases

### Phase 0 — Cadrage et engagement (1-2 mois)

- Signature de l'accord de partenariat
- Désignation des points de contact côté Ministère
- Choix de l'hébergeur souverain (ANPTIC, opérateur national, ou solution mixte)
- Définition des hôpitaux pilotes (recommandation : 1 CHU + 1 CMA + 1 CSPS)

### Phase 1 — Pilote (3-6 mois)

- Déploiement sur 3 établissements pilotes
- Formation du personnel (présentielle + e-learning)
- Migration progressive du papier vers le numérique avec phase de cohabitation
- Intégration française complète, premiers ajustements au contexte burkinabè
- Évaluation à 6 mois : indicateurs d'adoption, satisfaction utilisateurs, gains mesurés

### Phase 2 — Extension régionale (6-12 mois supplémentaires)

- Déploiement à l'ensemble des hôpitaux d'une région sanitaire pilote
- Intégration CARFO/CNSS pour la facturation
- Activation du module surveillance épidémiologique relié à la DGISS
- Mise en place du mode hors ligne mobile

### Phase 3 — Déploiement national (12-24 mois supplémentaires)

- Extension à toutes les régions sanitaires
- Intégration des langues nationales (Mooré, Dioula, Fulfuldé)
- Module télémédecine pour zones isolées
- Centre de formation national MediHub

---

## Diapositive 21 — Modèle économique

### Principes tarifaires

Notre proposition est conçue pour être **prévisible, transparente, et soutenable à long terme** pour le budget public.

| Composante | Modèle |
|---|---|
| **Mise en service initiale** | Forfait fixe par phase, conditionnée à des jalons mesurables (déploiement effectif, formation effective, données réellement migrées). |
| **Licence d'usage** | Abonnement annuel par établissement, dégressif au volume — un déploiement national coûte moins cher par hôpital qu'un déploiement de 3 hôpitaux. |
| **Maintenance et évolution** | Inclus dans l'abonnement. Pas de facturation à l'acte pour les correctifs de bugs. |
| **Hébergement** | Facturé au coût réel, sans marge — l'État paie uniquement ce que coûte réellement l'infrastructure (serveurs, stockage, bande passante). |
| **Formation** | Forfait par session, dégressif au volume. Programme « train-the-trainer » pour former des formateurs locaux et réduire la dépendance future. |
| **Personnalisations sur demande** | Devisés à l'unité, sur cahier des charges co-construit. |

### Engagements contractuels

- **SLA garanti** : disponibilité 99,5%, temps de réponse aux incidents critiques < 1 heure, temps de résolution < 4 heures.
- **Pas de coûts cachés** : tout est dans le contrat, pas de surprise après signature.
- **Clause de réversibilité** : à tout moment, l'État peut récupérer l'intégralité de son patrimoine de données et changer d'opérateur. Aucun verrouillage technique.

> **Notes pour l'orateur :** Ne pas annoncer de chiffres précis ici — les négocier en bilatéral avec le ministère selon la phase et le périmètre. Mais préciser systématiquement « le coût total sera connu et engagé avant signature, pas découvert après. »

---

## Diapositive 22 — Mesures de succès

### Indicateurs de pilotage proposés (à co-construire avec le Ministère)

#### Indicateurs d'adoption

- Nombre d'établissements actifs / nombre cible
- Nombre de soignants formés et utilisateurs actifs (login dans les 7 derniers jours)
- Taux de consultations saisies dans MediHub vs total (cible Phase 1 : > 80%)

#### Indicateurs d'efficience

- Réduction du temps moyen de consultation administrative (avant/après MediHub)
- Réduction des doublons de dossier patient
- Réduction du temps de référencement inter-établissements

#### Indicateurs de qualité des soins

- Taux de prescriptions générant une alerte interaction (signal de qualité prescription)
- Taux de patients perdus de vue après référencement
- Délai moyen entre demande d'examen et restitution du résultat

#### Indicateurs de surveillance épidémiologique

- Délai de détection d'un signal épidémique (jours entre premier cas et alerte)
- Taux de complétude des remontées vers DGISS

### Engagement de transparence

Tous les indicateurs ci-dessus seront publiés dans un **tableau de bord public mensuel** accessible au Ministère et à toute autorité de contrôle. Pas de zone d'ombre.

---

## Diapositive 23 — Pourquoi Bitnest Technologies ?

### Notre identité

Bitnest Technologies est une équipe d'ingénieurs spécialisés en architectures logicielles sécurisées de niveau entreprise, avec une mission claire : **mettre la technologie de pointe au service du développement de l'Afrique de l'Ouest**.

### Notre force

- **Expertise technique éprouvée** : architectures cloud-natives, sécurité, scalabilité, intégrations complexes.
- **Connaissance du contexte ouest-africain** : équipe basée en région, comprenant les réalités d'infrastructure (connectivité variable, contraintes budgétaires, besoins de formation).
- **Engagement pérenne** : nous ne sommes pas un consultant qui livre et part. Nous co-construisons sur le long terme.
- **Modèle commercial transparent** : pas de marges cachées sur l'hébergement, pas de coûts surprise, contrats clairs.

### Notre engagement envers le Burkina Faso

- Recrutement et formation d'**ingénieurs burkinabè** pour constituer un centre d'excellence local.
- Transfert progressif de compétences pour que l'État puisse à terme exploiter et faire évoluer la plateforme avec ses propres équipes.
- Partenariats avec les universités locales (UFR Sciences et Techniques, École Polytechnique de Ouagadougou) pour la recherche appliquée et les stages.

---

## Diapositive 24 — Risques identifiés et stratégies d'atténuation

Aucun projet d'envergure n'est sans risque. Voici les principaux que nous identifions et comment nous proposons de les gérer :

| Risque | Probabilité | Impact | Stratégie d'atténuation |
|---|---|---|---|
| **Résistance au changement du personnel soignant** | Moyenne | Élevé | Formation progressive, accompagnement présentiel, champions internes par établissement, cohabitation papier/numérique pendant 6 mois. |
| **Connectivité intermittente dans les établissements périphériques** | Élevée | Moyen | Mode hors ligne mobile (Phase 2), serveurs locaux de cache pour CSPS, synchronisation différée. |
| **Qualité variable des données saisies au démarrage** | Élevée | Moyen | Validations strictes côté serveur, contrôles de cohérence automatiques, audits périodiques, tableaux de bord de qualité données. |
| **Dépendance à un fournisseur unique (verrouillage)** | Risque perçu | Élevé | Engagement contractuel de réversibilité, formats ouverts, transfert de compétences vers équipes locales, code source mis en séquestre auprès d'un tiers de confiance. |
| **Incidents de sécurité (intrusion, fuite de données)** | Faible mais grave | Très élevé | Tests d'intrusion annuels par tiers indépendant, processus de réponse aux incidents documenté, assurance cyber-responsabilité, formation continue de l'équipe. |
| **Évolutions réglementaires** | Moyenne | Moyen | Veille juridique permanente, architecture flexible permettant adaptations rapides. |

---

## Diapositive 25 — Cas d'usage concret : référencement d'un patient (illustration)

**Scénario réel à présenter :**

Madame Sawadogo, 34 ans, enceinte de 7 mois, vit à Diébougou.

### Aujourd'hui (sans MediHub)

1. Elle se présente au CSPS de Diébougou pour saignements. Le sage-femme l'examine, écrit une note manuscrite, lui remet un papier de référence pour le CMA de Bobo-Dioulasso.
2. Elle voyage 4 heures en bus, présente le papier qui peut être perdu, illisible, ou incomplet.
3. À l'arrivée au CMA, le médecin doit l'interroger à zéro, refaire des examens (déjà faits au CSPS), perdre 2-3 heures de temps clinique.
4. Si elle est elle-même référée vers le CHU, même problème.
5. Aucune trace consolidée nulle part.

### Demain (avec MediHub)

1. Le sage-femme du CSPS saisit l'examen et le motif de référencement dans MediHub. Le système génère une référence électronique, envoyée instantanément au CMA cible.
2. Madame Sawadogo arrive avec son numéro d'identité ; le médecin du CMA voit immédiatement son dossier complet : antécédents, examens du CSPS, motif de référencement.
3. Le médecin gagne 2 heures de temps clinique, qu'il peut consacrer à d'autres patients.
4. Si nouveau référencement nécessaire vers le CHU, même fluidité.
5. Le ministère voit, dans son tableau de bord, le flux des référencements obstétricaux par région — données utiles pour planifier les ressources.

### Bénéfice systémique

Si chaque référencement gagne 2 heures de temps médical, et qu'on compte ~50 000 référencements par an au Burkina Faso, **c'est 100 000 heures de temps médical libérées chaque année** — l'équivalent de plusieurs dizaines de médecins à plein temps, sans recruter un seul professionnel supplémentaire.

---

## Diapositive 26 — Conditions de partenariat

### Ce que nous proposons

- **Contrat-cadre de partenariat technologique** sur une durée initiale de 5 ans, renouvelable.
- **Comité de pilotage paritaire** : 50% Ministère, 50% Bitnest, décidant des priorités d'évolution.
- **Bureau local Bitnest à Ouagadougou** dans les 6 mois suivant la signature, avec une équipe technique permanente.
- **Engagement de transfert de compétences** : à 5 ans, l'équipe locale doit pouvoir maintenir et faire évoluer la plateforme de manière autonome.

### Ce que nous demandons

- **Engagement politique au plus haut niveau** — un projet de cette envergure ne réussit qu'avec un sponsor ministériel direct.
- **Désignation d'un chef de projet ministériel** dédié à 100% à MediHub.
- **Accès aux référents métier** (médecins, infirmiers, gestionnaires hospitaliers) pour co-construire les ajustements au contexte.
- **Financement initial de la Phase 0 et Phase 1**, avec déclenchement des phases suivantes conditionné aux résultats mesurés.

### Garanties que nous offrons

- **Réversibilité totale** documentée et opposable.
- **Code source en séquestre** auprès d'un tiers de confiance neutre.
- **Audits annuels** par cabinet indépendant choisi par le Ministère.
- **Pas de clause d'exclusivité** abusive — l'État reste libre de ses choix.

---

## Diapositive 27 — Prochaines étapes proposées

### Court terme (4 semaines à venir)

1. **Démonstration technique en présentiel** au Ministère — 2 heures, présentation interactive de MediHub en environnement de pré-production avec données fictives représentatives du contexte burkinabè.
2. **Atelier de cadrage** avec les équipes techniques du Ministère, ANPTIC, et DGISS — identifier les contraintes d'intégration et d'hébergement.
3. **Visite éventuelle d'un établissement pilote candidat** — comprendre la réalité opérationnelle pour calibrer la Phase 1.

### Moyen terme (3 mois)

4. **Étude de faisabilité conjointe** finalisée, incluant : choix d'hébergement, périmètre exact Phase 1, calendrier détaillé, budget précis.
5. **Validation institutionnelle** par les instances compétentes (Conseil des Ministres si nécessaire, autorisations CIL).
6. **Signature du contrat-cadre de partenariat**.

### Démarrage Phase 1 (6 mois)

7. Constitution des équipes mixtes
8. Lancement opérationnel sur les 3 établissements pilotes

---

## Diapositive 28 — Conclusion

> **Le Burkina Faso a tout pour devenir un référent ouest-africain en matière de santé numérique.**

Ce qui manque aujourd'hui n'est ni la stratégie (elle existe depuis 2018), ni la volonté (le Ministère a démontré sa détermination à plusieurs reprises), ni les compétences locales (les ingénieurs burkinabè sont parmi les meilleurs de la sous-région). Ce qui manque, c'est **un partenaire technique sérieux, transparent, et engagé sur la durée**.

MediHub n'est pas une promesse — c'est un produit réel, opérationnel en pré-production, prêt à être déployé en commençant petit (3 établissements pilotes) et en montant progressivement en charge.

**Notre engagement** : que dans 5 ans, le système de santé burkinabè soit cité comme un modèle continental de digitalisation réussie — pas pour la technologie elle-même, mais pour ce qu'elle aura permis : moins de patients perdus de vue, plus de soignants libérés des tâches administratives, des épidémies détectées plus tôt, et un État qui maîtrise pleinement son patrimoine de données de santé.

**Nous sommes prêts. À votre service.**

---

## Diapositive 29 — Contact

### Bitnest Technologies — Équipe MediHub

**Tiego Ouedraogo**
Fondateur — Direction technique
✉️ tiegoouedraogo@gmail.com
✉️ Contact professionnel : via le site projet ou sur demande
✉️ Contact professionnel : via le site projet ou sur demande

**Site projet :** https://hms.bitnesttechs.com (en construction publique)
**Démonstration en pré-production :** sur demande, accès supervisé

> **Notes pour l'orateur :** Si la version finale doit comporter une équipe élargie, ajouter ici les co-fondateurs / responsables commerciaux / responsables programme avec leurs coordonnées. Pour le pitch initial, garder la liste courte = signal de réactivité.

---

## Diapositive 30 — Annexe A : Architecture technique détaillée

> *Cette annexe est destinée à l'équipe technique du Ministère et de l'ANPTIC. Pas à présenter en plénière — à fournir sur demande.*

### Composants logiciels

- **API Gateway** — Spring Cloud Gateway, gestion du rate limiting, observabilité.
- **Service métier `hospital-core`** — Spring Boot 3.x, Java 21, ~150 endpoints REST documentés OpenAPI 3.0.
- **Service authentification** — Keycloak 26 en mode standalone HA.
- **Base PostgreSQL 16** — schéma `security` (utilisateurs, rôles, audit), schéma `clinical` (patients, dossiers, prescriptions), schéma `admin` (configuration, référentiels).
- **Front portail** — Angular 18 avec lazy loading par module, PWA-ready, accessibilité WCAG 2.1 AA.
- **Apps mobiles** — Android Kotlin (compileSdk 34) + iOS Swift (iOS 16+).

### Performance et scalabilité

- Charge testée jusqu'à **500 requêtes/seconde** sur configuration standard 4 vCPU / 16 Go RAM.
- Architecture horizontalement scalable — capacité doublée en ajoutant un nœud.
- Cache Redis pour sessions et données de référence (réduction charge BD ~40%).

### Disponibilité

- Cible SLA : **99,5%** (équivaut à ~3,6 heures d'indisponibilité par mois maximum).
- Sauvegardes : continues (réplication streaming) + snapshots horaires + dumps quotidiens chiffrés.
- Plan de reprise d'activité (PRA) documenté, RTO < 4h, RPO < 15min.

---

## Diapositive 31 — Annexe B : Glossaire

| Acronyme / terme | Signification |
|---|---|
| **HMS** | Hospital Management System — Système de gestion hospitalière |
| **PHI** | Personal Health Information — Données de santé personnelles identifiables |
| **RBAC** | Role-Based Access Control — Contrôle d'accès basé sur les rôles |
| **SSO** | Single Sign-On — Authentification unique |
| **OIDC** | OpenID Connect — Standard d'authentification/identité |
| **OAuth2** | Standard d'autorisation déléguée |
| **MFA / TOTP** | Multi-Factor Authentication via Time-based One-Time Password |
| **FHIR** | Fast Healthcare Interoperability Resources — Standard d'échange de données de santé |
| **HL7** | Health Level Seven — Famille de standards d'interopérabilité santé |
| **DHIS2** | District Health Information System v2 — Plateforme de pilotage statistique santé largement déployée en Afrique |
| **CIM-11** | Classification Internationale des Maladies, 11ème révision (OMS) |
| **DGISS** | Direction Générale des Systèmes d'Information Sanitaires (Burkina Faso) |
| **CSPS** | Centre de Santé et de Promotion Sociale |
| **CMA** | Centre Médical avec Antenne chirurgicale |
| **CHR / CHU** | Centre Hospitalier Régional / Universitaire |
| **CARFO** | Caisse Autonome de Retraite des Fonctionnaires |
| **CNSS** | Caisse Nationale de Sécurité Sociale |
| **ANPTIC** | Agence Nationale de Promotion des TIC (Burkina Faso) |
| **CIL** | Commission de l'Informatique et des Libertés (Burkina Faso) |

---

## Diapositive 32 — Annexe C : Références et sources

### Documents stratégiques

- Stratégie Nationale de Santé Numérique du Burkina Faso 2018-2025
- WHO Global Strategy on Digital Health 2020-2025
- Africa CDC Digital Health Strategy 2022
- Plan National de Développement Économique et Social (PNDES) du Burkina Faso

### Standards techniques utilisés

- HL7 FHIR R4 ([https://hl7.org/fhir/R4/](https://hl7.org/fhir/R4/))
- OAuth 2.0 (RFC 6749), OpenID Connect 1.0
- ICD-11 (CIM-11) — OMS
- ISO 27001 (sécurité de l'information) — référentiel d'inspiration

### Pour vérification technique

- Architecture détaillée disponible sur demande sous accord de confidentialité
- Démonstration live en pré-production accessible avec compte temporaire supervisé
- Code source consultable sous accord de confidentialité

---

> **Fin du document de présentation.**
>
> Pour toute question, ajustement ou complément avant présentation officielle au Ministère, contactez l'équipe Bitnest.
>
> *Document version 1.0 — 13 mai 2026 — En français standard, registre administratif*
