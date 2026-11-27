# Règles cliniques provisoires et mise à jour à l'arrivée des cliniques

Ce document sert de référence tant qu'aucune clinique réelle n'utilise la plateforme. Il donne les règles retenues pour l'instant, leurs écarts avec le code et la marche à suivre pour les remplacer par les décisions de chaque clinique.

**Ces règles ne sont pas approuvées.** Elles restent en attente d'approbation clinique dans le [registre des approbations](approvals-register.md). Les recommandations ont été fournies le 7 novembre 2026 sans auteur nommé ni liens. Les sources sont citées telles qu'elles ont été données et n'ont pas été revérifiées ici.

Les constats CR-01 à CR-08 viennent de la [revue approfondie](clinical-rules-deep-review-2026-11-07.md), qui en donne les preuves et les tests d'acceptation.

## 1. Sang : compatibilité et délivrance en urgence

**Référence :**

- Une transfusion de routine de globules rouges exige l'identification du patient, le groupage ABO/RhD, la recherche d'anticorps et une épreuve de compatibilité adaptée.
- « Non testé » reste distinct de « compatible ».
- Une hémorragie mettant la vie en danger peut justifier des globules rouges sans épreuve de compatibilité terminée.
- Le groupe O est couramment utilisé quand le groupe est inconnu, et le choix RhD dépend du patient et du protocole local.
- Le groupe O ne garantit pas la compatibilité vis-à-vis des autres anticorps. Le plasma suit d'autres règles.

**Recommandation :** un circuit d'urgence distinct, qui enregistre le clinicien responsable, l'urgence, le motif, les tests en attente et les unités délivrées. Les résultats réels et les contrôles d'identité du patient et de l'unité sont conservés.

**Sources citées :** Lifeblood (consignes d'urgence), NZ Blood.

**Code :** `service/support/EmergencyReleasePolicy.java`. Le circuit d'urgence existe déjà.

**Choix locaux à faire confirmer :**

- Toute patiente et tout patient de sexe inconnu passent par la décision documentée, quel que soit l'âge.
- Une libération malgré une épreuve incompatible est autorisée par un médecin ou le directeur de laboratoire, car le rôle de médecin transfuseur n'existe pas.

## 2. Constantes du nouveau-né

**Référence :**

- L'interprétation dépend de l'âge gestationnel, des heures de vie, de la prématurité, de l'assistance respiratoire et de la méthode de mesure.
- La température visée après la naissance se situe autour de 36,5 à 37,5 °C.
- Signes de danger de l'OMS : fréquence respiratoire supérieure à 60/min, température supérieure à 37,5 °C ou inférieure à 35,5 °C, mauvaise alimentation, convulsions, tirage sous-costal sévère, absence de mouvement spontané.
- Ces signes déclenchent une orientation. Ils ne définissent pas à eux seuls la normalité.

**Recommandation :** accepter et conserver les valeurs anormales, les signaler clairement et déclencher une évaluation. Ne refuser que les formats, les unités ou les saisies manifestement erronés.

**Sources citées :** AHA (consignes néonatales), OMS (recommandations postnatales).

**Code :** `service/maternity/NewbornAlertRules.java`. Le code avertit sans bloquer, comme recommandé.

**Écarts :**

- **CR-06 :** une alerte numérique peut enregistrer un soutien thermique ou respiratoire, ou un protocole glycémique, comme commencé, même si la valeur saisie dit le contraire. Une alerte peut recommander un traitement, mais elle ne doit jamais l'enregistrer comme effectué. Ce défaut d'intégrité du dossier ne dépend d'aucune recommandation.
- **CR-04 :** une température de 37,6 à 37,9 °C donne seulement une alerte de prudence, alors que le parcours OMS retenu en fait un signe de danger. Les seuils NICE du code concernent un autre contexte, l'infection. Le parcours choisi doit être nommé, et l'escalade déjà plus stricte aux basses températures est conservée.
- **CR-05 :** aucun parcours fiable ne couvre la mauvaise alimentation, les convulsions, le tirage sous-costal sévère et l'absence de mouvement spontané. Il faut des observations structurées (présent, absent, non évalué), et un signe présent déclenche à lui seul une évaluation.
- Les seuils du code viennent d'autres sources (Royal Children's Hospital, NICE NG195, Société canadienne de pédiatrie). La clinique devra choisir sa référence.

## 3. Paracétamol

**Référence par voie orale (MSF) :**

| Population | Dose | Maximum |
| --- | --- | --- |
| Moins de 1 mois | 10 mg/kg par dose, 3 à 4 fois par jour | 40 mg/kg/jour |
| Enfant à partir de 1 mois | 15 mg/kg par dose, 3 à 4 fois par jour | 60 mg/kg/jour |
| Adulte | 1 g par dose, 3 à 4 fois par jour | 4 g/jour |

Ces limites sont celles d'une seule recommandation et ne valent pas pour toutes les voies ni pour tous les patients. La prématurité, le faible poids et les maladies du foie demandent un examen à part. Les schémas intraveineux et rectaux ne reprennent pas automatiquement les règles orales. Le NHS indique au moins 4 heures entre deux doses habituelles chez l'adulte et précise qu'une dose plus faible peut être nécessaire en dessous de 50 kg.

**Recommandation :** calculer selon le protocole applicable à l'âge, au poids et à la voie. Additionner le paracétamol de tous les produits et de toutes les voies sur 24 heures glissantes. Un motif seul ne permet pas de dépasser une limite ou un intervalle : il faut une décision de prescription autorisée.

**Sources citées :** MSF (paracétamol oral), NHS.

**Code :** `cdshooks/rules/AdultParacetamolRule.java` et `cdshooks/rules/PediatricDoseRule.java`.

**Écarts :**

- **CR-01 :** la voie de la prescription n'atteint pas les règles de dose. Il faut la transmettre, avec la forme, à l'aperçu, à la création, à la modification et à la signature. Une voie absente ou non prise en charge donne « évaluation impossible, examen requis ».
- **Limites de l'enfant :** des protocoles propres à chaque hôpital sont légitimes, et l'absence des chiffres MSF n'est pas un défaut. Ne pas charger une table orale unique pour tous. En revanche, une limite doit indiquer sa voie, sa tranche d'âge, sa source, sa version et son statut d'approbation, et ce statut n'est aujourd'hui pas une condition d'application. En dessous de 3 mois, le verdict automatique en mg/kg est suspendu et un examen est demandé, mais le plafond quotidien absolu reste contrôlé d'abord.
- **CR-02 :** une alerte de dépassement chez l'adulte n'exige pas de décision explicite à la signature, et une signature générique n'en tient pas lieu. Politique proposée : conserver le brouillon, retenir la signature courante, et n'admettre qu'une exception explicite du prescripteur, liée à l'alerte et à la version de la prescription. Pour une contre-indication, un nouveau motif est déjà exigé à la signature du médecin. Cela établit une décision authentifiée, sans établir que l'exception est cliniquement permise.
- **CR-03 :** les prescriptions actives ne représentent pas l'exposition sur 24 heures glissantes. Les prescriptions récemment arrêtées, les associations et les doses administrées peuvent manquer. Il faut séparer l'exposition estimée d'après les prescriptions de l'exposition documentée par les administrations, et afficher ce qui reste inconnu.

## 4. Classification périnatale

**Référence :**

- L'OMS définit la période périnatale de 22 semaines de gestation révolues jusqu'aux jours 0 à 6 après la naissance.
- La période néonatale couvre les jours 0 à 27.
- Un décès à exactement sept jours révolus sort de la période néonatale précoce.
- Les seuils de déclaration peuvent différer de ces définitions.

**Recommandation :** enregistrer séparément l'âge gestationnel, le statut né vivant ou mort-né et les dates de naissance et de décès. Déduire la classification de ces faits et de la norme de déclaration choisie. Un âge gestationnel manquant donne « informations insuffisantes », jamais une classification inventée.

**Source citée :** OMS, guide de référence de la CIM-11.

**Code :** `service/support/DeathClassificationRules.java`. Le décès s'enregistre toujours, et seule la classification peut être refusée.

**Écarts :**

- **CR-07 :** les décès néonatals tardifs sont comptés dans le total périnatal, alors qu'ils dépassent les jours 0 à 6 de l'OMS.
- **CR-08 :** seule la borne supérieure est contrôlée, si bien qu'un décès à 2 jours peut passer pour « néonatal tardif ». La soustraction de dates perd la précision de l'heure de naissance. L'âge gestationnel et le statut né vivant ou mort-né manquent sur ce dossier.
- **Précision :** le choix de la classification par le clinicien n'est pas un défaut en soi. Un âge gestationnel manquant laisse inconnue l'éligibilité qui en dépend, sans effacer une classification néonatale précoce ou tardive établie à partir d'une naissance vivante et d'heures connues.

## 5. Contestation d'un dossier de décès (politique proposée)

**Recommandation :**

- Un médecin autorisé de l'hôpital traitant vérifie l'identité et documente que le patient est vivant.
- Le dossier passe à « statut de décès contesté », et la déclaration d'origine est conservée.
- Les blocages liés au décès sont levés dans le périmètre de soins autorisé.
- Un examen administratif urgent est ouvert.
- Les contrôles de prescription, d'allergie, de médicament et de transfusion continuent.
- Un retard de l'examen ne rétablit pas automatiquement le blocage.

Il s'agit d'une politique de sécurité proposée, pas d'une norme clinique universelle.

**Code :** `service/impl/DeathDisputeServiceImpl.java`, `service/scheduled/DeathDisputeEscalationScheduler.java`.

## 6. Prescriptions pendant une contestation (politique proposée)

**Recommandation :**

- La reprise des soins déclenche l'examen des prescriptions en cours au moment de l'enregistrement du décès.
- Les doses non commencées, les médicaments préparés mais non délivrés et les demandes de sang en attente restent suspendus jusqu'à ce qu'un prescripteur autorisé les reconfirme, les remplace ou les arrête.
- Les prescriptions annulées, arrêtées ou liées à un séjour terminé restent inactives. Les nouvelles prescriptions suivent les contrôles habituels.
- Les perfusions en cours exigent une évaluation clinique immédiate. Le logiciel ne les arrête ni ne les relance du seul fait de la contestation.

**Source citée :** OMS, sécurité des médicaments (principe de conciliation médicamenteuse).

**Code :** `service/impl/OrderReviewHoldServiceImpl.java`, `service/support/OrderReviewHoldGuard.java`. Ce travail est en cours sur `fix/r36-order-review-hold` et n'est pas encore fusionné.

**Qui reconfirme :** un prescripteur autorisé, et non « un clinicien autorisé » comme écrit plus haut. Il faut un droit de prescription en cours pour cette prescription et cet hôpital. L'examen d'un pharmacien ne donne pas ce droit. Un autre prescripteur autorisé peut décider à la place de l'auteur d'origine.

## 7. Vérification en lecture seule

Il s'agit d'un test d'exploitation, distinct des règles cliniques. Il doit vérifier que :

- les lectures restent disponibles ;
- des écritures représentatives sont refusées par le serveur ;
- les écritures refusées ne laissent aucune modification partielle ;
- le fonctionnement normal est rétabli ensuite.

Il faut des dossiers de test isolés, un créneau défini, des conditions d'arrêt et un responsable du rétablissement. D1404 et D1407 restent non vérifiés tant que ce test autorisé n'a pas été exécuté et ses preuves enregistrées.

## 8. Hypertension sévère : grossesse, post-partum et adulte (décisions provisoires du 2026-11-14 et du 2026-11-15)

Une seule règle, deux portées. Le 2026-11-14 ne couvrait que l'après-naissance (D1890, PR #1167). Le 2026-11-15, après la QA r54 (une femme adulte sans accouchement à 164/114 enregistrée « non significative »), l'utilisateur l'a étendue à la grossesse et à l'adulte, et a ramené la période post-partum à 42 jours.

### 8.1 Règle obstétricale (grossesse et post-partum)

**Référence (fournie par l'utilisateur) :** pendant la grossesse et après la naissance, une tension systolique ≥ 160 mmHg **ou** diastolique ≥ 110 mmHg (bornes incluses, une seule valeur suffit) est une hypertension sévère qui demande un avis obstétrical urgent. Déclenchent : 160/80, 120/110, 162/112, 164/114. Ne déclenche pas : 159/109.

**Recommandation :** une alerte URGENTE « avis obstétrical urgent requis », qui demande aussi de recontrôler la tension. Elle ne bloque jamais l'enregistrement, l'acquittement est facultatif, et la première mesure alerte (pas de délai de confirmation de 15 minutes).

**Portée, lue dans le dossier :**

- **grossesse :** un travail en cours (dans n'importe quel hôpital), ou les antécédents maternels courants (dernière version) dont la grossesse couvre le jour de la mesure : de la date prévue d'accouchement − 280 jours à la date prévue + 14 jours (date échographique, sinon date enregistrée, sinon DDR + 280 jours), sans accouchement enregistré entre le début de la grossesse et la mesure. C'est la lecture déjà utilisée par l'alerte de grossesse du panneau du médecin (D971) ;
- **post-partum :** un accouchement enregistré (dans n'importe quel hôpital) le jour de la mesure ou dans les **42 jours** qui précèdent (jour 42 inclus, jour 43 exclu). Avant le 2026-11-15 : 365 jours. Au-delà de 42 jours, la règle adulte s'applique, y compris sur l'observation post-partum ;
- une patiente enregistrée de sexe masculin n'est jamais dans cette portée ;
- **confidentialité (revue r54) :** la grossesse, le travail et l'accouchement ne sont lus que dans les hôpitaux que la politique d'accès aux dossiers autorise pour la personne qui saisit (l'hôpital où elle agit, plus les autres sur une relation de soins permise, jamais au-delà d'un refus de partage de la patiente). Si le partage n'est pas permis, seuls les dossiers de cet hôpital comptent, et la règle adulte s'applique ; le texte de l'alerte n'en dit rien. Une alerte qui repose sur le dossier d'un autre hôpital est tracée dans le registre des divulgations (`RECORD_SHARE`).

**Sources citées (par l'utilisateur, non revérifiées ici) :** NICE (hypertension pendant la grossesse et après l'accouchement) ; ACOG Committee Opinion 692 (hypertension sévère aiguë pendant la grossesse et le post-partum) ; ACOG, suivi post-partum de 6 semaines (soins urgents).

### 8.2 Règle adulte

**Référence (fournie par l'utilisateur) :** un adulte (18 ans et plus le jour de la mesure) hors règle obstétricale ; **et, quel que soit son âge, une patiente qui a accouché, une fois passés les 42 jours** (décision provisoire du 2026-11-15, revue r54 : une mère de 16 ans au jour 50 à 200/130 n'avait aucune alerte), avec une tension systolique ≥ 180 mmHg **ou** diastolique ≥ 120 mmHg (bornes incluses). Déclenchent : 180/80, 120/120. Ne déclenchent pas : 179/119, 164/114.

**Recommandation :** un AVERTISSEMENT « Hypertension sévère — recontrôler la tension au repos et rechercher des signes d'atteinte d'organe ». Il devient URGENT (et l'équipe est prévenue) seulement si la personne qui saisit coche un signe d'atteinte d'organe : douleur thoracique, signes neurologiques, dyspnée, troubles visuels, céphalée sévère (liste facultative, sous les constantes du poste infirmier et du triage). Jamais bloquant, acquittement facultatif, la première mesure alerte. Aucune règle pédiatrique : avant 18 ans, sans accouchement enregistré, rien ne change.

**Sources citées (par l'utilisateur, non revérifiées ici) :** ACC/AHA 2017 et 2025 (au-delà de 180/120, c'est l'atteinte d'organe qui décide de l'urgence) ; ESH 2023 (grade 3) ; NEWS2 (la PAS haute n'est cotée qu'à partir de 220, d'où l'absence d'alerte avant cette règle).

### 8.3 Code et surfaces

**Code :** `service/vitals/SevereHypertension.java` (remplace `service/maternity/PostpartumSevereHypertension.java`), seul endroit qui porte les seuils, la période de 42 jours, la lecture de la grossesse et l'âge adulte. Surfaces :

- l'observation post-partum de la sage-femme (`PostpartumCareServiceImpl`) : alerte URGENTE de type HYPERTENSION jusqu'au jour 42 ; au-delà, alerte de PRUDENCE « recontrôler la tension au repos » si la règle adulte se déclenche ;
- toute saisie de constantes, poste infirmier, triage et API (`VitalSignJudgement`) : mesure « cliniquement significative » ; `postpartumAlerts` (règle obstétricale, URGENTE, équipe prévenue) ou `hypertensionAlerts` (règle adulte, AVERTISSEMENT, ou URGENTE avec un signe d'atteinte d'organe, l'équipe n'étant prévenue qu'alors) ;
- le panneau d'alertes du médecin, sur la dernière tension des 24 dernières heures : `PregnancyHypertensionProtocolRule` (grossesse, inchangée, seuil sévère lu dans la règle commune), `PostpartumSevereHypertensionRule` (post-partum jusqu'au jour 42, carte CRITIQUE), `AdultSevereHypertensionRule` (adulte, carte AVERTISSEMENT ou CRITIQUE, levée par une mesure suivante sous les seuils) ;
- le partogramme (`LaborServiceImpl`) lit aussi le seuil sévère dans la règle commune.

Les signes d'atteinte d'organe sont enregistrés avec la mesure (colonne `organ_damage_symptoms`, migration V299).

**Test de garde :** `SevereHypertensionGuardTest` vérifie que chaque surface passe par la règle commune, qu'aucun fichier Java ne compare une tension à 160/110 ou 180/120 ni ne déclare une constante de tension à ces valeurs, et que le portail ne porte aucun de ces seuils.

**Choix à faire confirmer :**

- par le responsable d'obstétrique : les deux seuils ; la période de 42 jours ; la lecture de la grossesse (travail en cours, ou antécédents maternels courants, tous hôpitaux) ; les destinataires de l'alerte ;
- par le responsable clinique / médecine interne : les seuils adultes ; leur extension aux mères de moins de 18 ans après les 42 jours ; la liste des signes d'atteinte d'organe ; l'âge inconnu traité comme adulte ; l'équipe prévenue seulement pour l'alerte URGENTE ; la carte AVERTISSEMENT sans notification ;
- pour les deux : la première mesure alerte (pas de délai de confirmation), et le panneau du médecin juge la dernière tension mesurée.

## 9. Admission en travail d'une mineure (décision provisoire du 2026-11-14)

**Constat (QA r53) :** une fillette de 10 ans a été admise en travail par une sage-femme sans aucun avertissement. La limite logicielle `ObstetricEligibility.MIN_OBSTETRIC_AGE_YEARS = 8` n'est pas une approbation clinique.

**Règle provisoire (fournie par l'utilisateur) :**

- l'admission est permise tout de suite : elle n'est jamais bloquée ni retardée, aucune seconde signature n'est demandée avant l'admission ou les soins ;
- l'admission en travail d'une mineure de la tranche couverte ouvre une alerte URGENTE « Vigilance protection de l'enfance — revue requise » et une demande de revue obstétricale senior ;
- une revue documentée par le clinicien responsable ou l'équipe de protection de l'enfance est obligatoire : la tâche reste ouverte jusqu'à ce qu'elle soit consignée, sans jamais bloquer les soins ;
- les détails ne sont visibles que par l'équipe soignante et les administrateurs de l'hôpital concerné ; jamais par l'accueil, la facturation, le portail patient, les mandataires ou la famille, les applications natives, les notifications ou SMS aux proches, les exports ou les autres hôpitaux ; la famille n'est jamais prévenue automatiquement ;
- le texte est neutre : il dit qu'une revue est requise, jamais qu'un abus est constaté ou soupçonné.

**Source citée :** recommandations de l'OMS, telles que données par l'utilisateur, non revérifiées ici.

**Seuil :** une seule valeur réglable, `hms.safeguarding.minor-labour.age-under-years`, par défaut **moins de 13 ans révolus à l'admission** (provisoire). L'utilisateur a confirmé que 10 ans est couvert ; toute tranche plus large, toute obligation de signalement et tout destinataire d'escalade restent à approuver localement. Aucun signalement légal ni escalade externe n'est fait.

**Code :** `service/safeguarding/MinorLabourSafeguarding.java` porte le seuil et décide ; `SafeguardingReviewWorker` ouvre la revue après l'enregistrement, dans sa propre transaction (un échec n'annule jamais l'admission) et dépose deux tâches URGENTES à l'hôpital de l'admission : la revue de protection de l'enfance (médecins, chirurgiens, sages-femmes) et la revue obstétricale senior (médecins, chirurgiens). Une seule revue ouverte par patiente et par hôpital (migration V296, table `clinical.safeguarding_reviews`, note de revue chiffrée). Quatre chemins l'appellent : l'admission de type « Travail et accouchement », le début d'un épisode de travail, la poursuite d'un travail transféré et l'arrivée sans transmission (chaque hôpital ouvre sa propre revue, celle d'un autre hôpital n'est jamais montrée), plus l'admission par HL7 si l'interface est configurée en travail. La revue se lit seulement par `GET /safeguarding/...` (bandeau du dossier) et se consigne par un médecin ou un chirurgien de l'hôpital ; la consignation ferme les deux tâches.

**Tests de garde :** `MinorLabourSafeguardingPathGuardTest` (tout chemin qui écrit une admission en travail appelle le contrôle), `SafeguardingAccessGuardTest` (la revue ne sort que par son contrôleur, réservé à l'équipe soignante et à l'administrateur), `SafeguardingReviewAccessTest` et `SafeguardingReviewServiceTest` (refus de l'accueil, de la facturation, du patient et de ses mandataires, des autres hôpitaux), `MinorLabourSafeguardingTest` (10 et 12 ans déclenchent, 13 ans et plus non), `AdmissionServiceImplTest` (l'admission réussit même si la revue ne peut pas être ouverte).

**Choix à faire confirmer par le responsable d'obstétrique et le responsable de la protection de l'enfance :**

- la tranche couverte (moins de 13 ans aujourd'hui) et, le cas échéant, une tranche plus large ;
- qui consigne la revue (médecin ou chirurgien de l'hôpital aujourd'hui ; la sage-femme et l'infirmier la voient sans la consigner) ;
- les destinataires des deux tâches et une éventuelle escalade si la revue reste ouverte ;
- toute obligation de signalement légal ou externe (aucune aujourd'hui) ;
- l'âge est compté en années révolues à la date d'admission ; sans date de naissance, aucune revue n'est ouverte.

## 10. Imagerie urgente et préautorisation (décision provisoire du 2026-11-15)

**Constat (QA r53, D1925) :** depuis #1169, un examen dont la préautorisation est requise ne démarre pas sans son numéro, y compris en urgence. Un examen urgent pouvait donc attendre la réponse de l'assureur.

**Règle provisoire (fournie par l'utilisateur) :**

- un examen dont la priorité est **URGENT** ou **STAT** démarre sans le numéro de préautorisation : l'imagerie urgente n'est jamais bloquée par la préautorisation ;
- la demande porte alors « Préautorisation en attente » dans la liste Imagerie, le volet de la demande et la carte Imagerie du dossier ;
- les agents de facturation (rôle « Spécialiste de la facturation ») de l'hôpital de la demande reçoivent une tâche dans leur corbeille pour obtenir l'autorisation après coup ;
- l'enregistrement du numéro (volet de la demande, par un médecin ou un radiologue, à tout stade de l'examen, même terminé ou interprété) lève le drapeau et ferme la tâche ;
- un examen de routine (priorité ROUTINE, ou sans priorité) reste refusé sans numéro, comme avant ;
- un examen interprété (compte rendu signé) sans numéro est signalé de la même façon, jamais refusé ;
- qui peut démarrer un examen ne change pas.

**Sources citées (par l'utilisateur, non revérifiées ici) :** EMTALA, aux États-Unis : aucune autorisation préalable ne peut être demandée avant le dépistage et la stabilisation d'une urgence ; elle peut l'être ensuite. La règle de l'assurance maladie universelle du Burkina Faso (CNAMU) reste à confirmer.

**Code :** `service/support/ImagingPreAuthorisationPolicy.java` (priorités exemptées, décision de démarrage, drapeau `authorization_pending_since`, migration V297) ; `service/imaging/ImagingPreAuthorisationFollowUp.java` (tâche de facturation, déposée après validation dans sa propre transaction : un échec n'annule jamais le démarrage) ; `PUT /imaging/orders/{id}/authorization` pour enregistrer le numéro.

**Tests de garde :** `ImagingPreAuthorisationPolicyGuardTest` (tout chemin qui écrit le statut d'une demande d'imagerie consulte la politique), `ImagingPreAuthorisationPolicyTest`, `ImagingPreAuthorisationFollowUpTest`, `ImagingOrderServiceImplTest` (`*_rule1`), `InBasketRecipientGuardTest` (la facturation peut lire sa corbeille).

**Choix à faire confirmer par le responsable de radiologie (et la règle CNAMU) :**

- les priorités exemptées (URGENT et STAT aujourd'hui) ;
- les destinataires de la tâche (spécialistes de la facturation de l'hôpital ; sans eux, aucune tâche n'est déposée et seul le drapeau reste visible) ;
- qui enregistre le numéro (médecin ou radiologue aujourd'hui ; la facturation ne l'enregistre pas elle-même) ;
- un délai ou une relance si l'autorisation reste en attente (aucun aujourd'hui).

## 11. Motif pour « Commander quand même » en imagerie (décision provisoire du 2026-11-15)

**Constat (QA r53, D1921) :** depuis #1169, l'alerte de doublon d'imagerie s'affiche avant l'envoi, mais « Commander quand même » n'enregistrait rien sur la demande.

**Règle provisoire (fournie par l'utilisateur) :**

- « Commander quand même » exige un motif choisi dans une liste fixe : changement clinique ; examen précédent inadéquat ou indisponible ; région ou question clinique différente ; autre (note obligatoire) ;
- la demande garde le motif, la note, qui et quand ; le volet de la demande les affiche ; la piste d'audit enregistre le dépassement avec le code du motif (jamais la note) ;
- le serveur refuse une demande qui se dit « commandée quand même » sans motif, ou avec « autre » sans note ; une demande sans doublon trouvé n'enregistre aucun dépassement.

**Source citée (par l'utilisateur, non revérifiée ici) :** études d'aide à la décision sur la répétition d'imagerie : une justification obligatoire choisie dans une liste prédéfinie a évité environ un scanner répété sur 13.

**Code :** `enums/ImagingDuplicateOverrideReason.java`, `service/imaging/ImagingDuplicateOverride.java` (validation, enregistrement, audit `IMAGING_ORDER_DUPLICATE_PLACED`), colonnes de la migration V297 (note chiffrée par `EncryptedStringConverter`). Même principe que l'alerte de doublon du laboratoire (#1150).

**Tests de garde :** `ImagingOrderServiceImplTest` (`*_rule2` : chaque motif, « autre » sans note refusé, motif manquant refusé), `ImagingDuplicateOverrideTest`, `ImagingOrderEncryptionWiringTest`, `AuditDescriptionCoverageTest`, `imaging-r53.spec.ts`.

**Choix à faire confirmer par le responsable de radiologie :**

- la liste des motifs ;
- la longueur de la note (500 caractères) ;
- la fenêtre de doublon (30 jours par défaut, 90 au plus, inchangée).

## Mise à jour à l'arrivée d'une clinique

1. **Avant la mise en service,** remettre ce document et le registre à chaque approbateur de la clinique, avec la version exacte de chaque règle. Cette version correspond au fichier indiqué ci-dessus et au commit déployé.
2. **Recueillir la décision,** règle par règle : acceptée, modifiée ou refusée, avec les seuils, les exceptions et la prise en charge des urgences.
3. **Si la règle est acceptée sans changement,** remplir le dossier du registre. Ne rien remplir sans la décision écrite de l'approbateur.
4. **Si la règle change,** ouvrir une PR depuis `develop` qui modifie le code et ses tests, met à jour la source citée dans le commentaire du fichier, puis corrige ce document. Ne jamais modifier une migration déjà appliquée : en ajouter une nouvelle.
5. **Vérifier sur l'application en cours d'exécution :** relancer sur dev le parcours clinique concerné, y compris les cas d'urgence et les refus. Enregistrer l'approbation seulement contre la version réellement déployée.
6. **Si une règle est modifiée plus tard,** l'approbation précédente ne vaut plus pour la nouvelle version. Refaire les étapes 2 à 5.

**Attention, plusieurs cliniques :** les règles ci-dessus sont des constantes communes à toute la plateforme, sauf les limites pédiatriques du catalogue, propres à chaque hôpital. Une modification s'applique donc à tous les hôpitaux. Si deux cliniques demandent des seuils différents, il faudra d'abord concevoir un réglage par hôpital, qui n'existe pas aujourd'hui.

**Données existantes :** une nouvelle règle ne reclasse pas les anciens dossiers et ne recalcule pas les anciennes alertes. Toute correction rétroactive est une décision à part, à faire approuver.


## Règles politiques 2026-11-20 — PR #1242 (fusionnée sur develop 89cbf463d)

- **Données sensibles et accès famille (provisoire, 2026-11-20).** Catégories masquées aux proches : VIH, IST, hépatites B/C, grossesse, contraception/IVG, violences, santé mentale, addictologie, tests génétiques, drépanocytose (adulte) ; tuberculose en accès restreint (périmètre explicite). Source : rapport « Burkina clinic policy decisions », ligne 4 ; Loi 030-2008 art. 14-18 ; Code pénal art. 525-1. Pour modifier : `ProxySensitivityPolicy` (règle par catégorie), `SensitivityCategory`.
- **Résultat VIH d'un mineur (provisoire).** Masqué ; communiqué à un parent ou tuteur vérifié par un médecin ou une sage-femme qui prend en charge le patient (jamais le titulaire de l'accès lui-même), avec motif, tracé. **Seul un résultat VIH est communicable** ; un résultat lié à des violences ne l'est jamais (autorités uniquement, Code pénal art. 525-2) ; les autres catégories ne le sont pas pour l'instant. Source : Loi 030-2008 art. 17-18 ; décision du propriétaire du 2026-11-21. Pour modifier : `ProxySensitivityPolicy` (`MINOR_RULES`, `releasable`), `MinorResultReleaseServiceImpl`.
- **Âge de confidentialité des adolescents : 12 ans (provisoire).** Réglable par établissement (0-18). Pour modifier la valeur par défaut : `ProxySensitivityPolicy.DEFAULT_ADOLESCENT_AGE`.
- **Liste recommandée des examens sensibles (provisoire, version 2026-11-20-provisional).** Pour modifier : `RecommendedLabSensitivity.RULES` et `VERSION`.

## Règles politiques 2026-11-20 — PR #1243 (fusionnée sur develop bbed1e39a)

- **Sage-femme et nouveau-né (2026-11-20, provisoire).** Une sage-femme admet le nouveau-né d'un accouchement auquel elle a participé, pendant la période néonatale (né depuis 28 jours au plus ; au-delà, un médecin admet), et en est la clinicienne responsable ; elle attribue les lits et berceaux des unités de maternité ; un berceau de néonatologie attribué par elle crée une tâche urgente pour les médecins, retirée quand le séjour quitte ce berceau ; un signe de danger du nouveau-né atteint toujours un médecin. Rien ne bloque l'enregistrement. Les infirmiers n'admettent pas de nouveau-né. Pour changer : `MidwifeNewbornCare`, `BedAssignmentService.requireWardFitsPatient`, `ObstetricAlertEscalationWorker.withAPhysician`. Source : Politique et normes SR 2010 ; CSP art. 65, 134.
- **Technicien d'imagerie (2026-11-20, provisoire).** Rôle `ROLE_IMAGING_TECHNICIAN` : accueil, démarrage, fin ou interruption motivée de l'examen, note technique, contraste, dose, images clés, lecture des comptes rendus diffusés des examens qu'il a réalisés. Jamais de prescription, de compte rendu, de signature, ni de retrait d'image après signature. Le rôle compte dans l'établissement où il est détenu. Réglage par établissement « Échographie par un non-médecin », par défaut médecins uniquement : le technicien ne peut ni démarrer, ni terminer, ni interrompre une échographie, ni en saisir la réalisation ; activé, il le peut. Le compte rendu reste au médecin dans tous les cas. Pour changer : `ImagingExamRecord`, `ImagingTechnicianScope`, `ImagingReadRoleGuardTest`. Source : CSP français R4351-2 ; IHE Scheduled Workflow.

## Règles politiques 2026-11-20 — PR #1244 (fusionnée sur develop 0bd6f399f)

## 12. Renouvellement d'ordonnance (décision provisoire du 2026-11-20)

**Règle provisoire (rapport « Burkina clinic policy decisions », décision 1) :**

- seul un prescripteur autorisé approuve un renouvellement : un médecin pour toutes les listes ; un infirmier ou une sage-femme pour les listes I et II seulement (dans le produit : médicament non contrôlé) ; jamais un pharmacien ;
- les stupéfiants et les médicaments contrôlés ne sont jamais renouvelés : nouvelle ordonnance ;
- le pharmacien examine la demande et la transmet avec ses remarques, refuse de délivrer avec un motif (le patient et le prescripteur sont prévenus) et délivre une fois le renouvellement approuvé ;
- un renouvellement déjà inscrit sur l'ordonnance est une délivrance ordinaire, libérée par la pharmacie ;
- le réglage « prolongation exceptionnelle par le pharmacien » existe par établissement, désactivé, sans aucun comportement derrière.

**Source citée (non revérifiée ici) :** Code de la santé publique du Burkina Faso (1994, consolidé 2005), art. 159, 274, 276, 279-280, 285, 287.

**Code :** `RoleValidator.canApproveRenewal`, `RefillApprovalServiceImpl` (`decide`, `pharmacistReview`, `pharmacistRefuse`, `releaseAsPrescribed`), `RefillApprovalController`, migration V345 (`refill_requests.decided_by`, `decision_basis`, `pharmacist_note`), `HospitalBillingPolicy.pharmacistExceptionalRenewal`.

**Tests de garde :** `RenewalApprovalGuardTest`, `RefillApprovalServiceImplTest$PolicyRuleOne`, `refill-approval-list.component.spec.ts`.

**Choix à faire confirmer :** absence de rôle dentiste (un dentiste signe comme médecin) ; listes I et II non codées au catalogue ; durée et nombre de renouvellements (arrêté de l'art. 287 non trouvé).

### 13. Consultation et délivrance « à facturer » (décision provisoire du 2026-11-20)

**Règle provisoire (même rapport, décision 6) :**

- une consultation par visite où elle a eu lieu ; aucune consultation automatique sur une admission programmée, en hôpital de jour, d'un nouveau-né, en travail ou par transfert (réglage par établissement, désactivé par défaut) ;
- **une seule consultation par épisode d'urgence (décision du propriétaire du 2026-11-20, provisoire) :** une admission en urgence, urgente ou en observation qui suit un passage aux urgences déjà facturé le même jour ne reçoit pas de seconde ligne de consultation ; une admission directe garde sa ligne ;
- une demande de remboursement ne couvre une délivrance au comptoir que si la facturation, le contrôle des demandes, la comptabilité ou l'administrateur de l'établissement l'a envoyée ou a enregistré l'acceptation ou le paiement du payeur, et jamais la personne qui remet le médicament ; ce fait est enregistré au moment où il a lieu et n'est jamais réécrit (un départ ou une étape ultérieure ne change pas une journée clôturée) ;
- une délivrance ne sort non payée que : sur le compte d'un séjour ouvert (sans approbation) ; sur une prise en charge par un tiers vérifiée par la facturation contre le bon ; sur un crédit accordé par la comptabilité ou l'administrateur, avec un motif et un plafond facultatif ; ou par une dérogation d'urgence du pharmacien, motivée, journalisée et revue ensuite par la comptabilité ;
- jamais la même personne : celui qui approuve n'a pas demandé, celui qui remet n'a pas approuvé, celui qui revoit n'a pas dérogé ;
- tous les reports et dérogations figurent au rapprochement de la pharmacie et à son export.

**Source citée (non revérifiée ici) :** structure tarifaire publique (lettres clés C et Cs, journée d'hospitalisation) ; principes de contrôle des fonds renouvelables de médicaments (WHARC, MSH MDS-3 ch. 13) ; directive « aucun patient refoulé avant évaluation ».

**Code :** `DispenseBillingDeferralService` (`request`, `approve`, `reject`, `cancel`, `review`, `admit`), `DispenseServiceImpl` (les deux chemins de remise), `FillClaimCover`, `ClaimVouching`, `ConsultationChargeSource`, `EmergencyEpisodeConsultation`, `HospitalBillingPolicyService`, migration V345.

**Tests de garde :** `DeferredBillingGuardTest`, `DispenseBillingDeferralServiceTest`, `FillClaimCoverTest`, `EmergencyEpisodeConsultationTest`, `HospitalBillingPolicyServiceTest`, `billing-deferrals.spec.ts`.

**Choix à faire confirmer :** qui approuve un crédit et jusqu'à quel montant ; le service social (aucun rôle dans le produit) ; l'inscription à la gratuité ; l'épisode d'urgence reconnu par le jour (aucun lien explicite entre le passage et l'admission) ; les rôles qui répondent d'une demande de remboursement ; le report des délivrances d'hospitalisation sur la facture du séjour (non construit).

## Règles politiques 2026-11-20 — PR #1241 (fusionnée sur develop 305d89484)

### Alerte précoce maternelle (politique 2026-11-20, décision 5)
- **Règle** : MEWS national NHS England 2024 (6 constantes, 0–12, pouls post-natal à partir de 48 h, 4 niveaux), préoccupations supplémentaires (drapeaux), complément CRADLE (TA ≥160/≥110 rouge ; indice de choc ≥0,9 jaune, ≥1,7 rouge), valeurs d'alerte du Guide OMS de soins du travail pendant le travail. NEWS2 n'est ni affiché ni utilisé pour une femme enceinte ou en post-partum (42 jours).
- **Où** : `MaternalEarlyWarningRules` (`hms.maternity.ews.*`), `MaternalEarlyWarning`, `MaternalEarlyWarningService`.
- **Pour changer un seuil** : modifier la propriété (ou la valeur par défaut) ; `MaternalEarlyWarningRulesGuardTest.theDefaultsAreThePublishedValues` épingle les valeurs publiées et doit être mis à jour avec la décision signée. Désactivation : `hms.maternity.ews.enabled=false` (retour à « NEWS2 non validé »).
- **Appels (provisoire, décisions 2026-11-20)** : dans les 30 minutes qui suivent un appel (`hms.maternity.ews.escalation.page-cool-down-minutes`, 0 = désactivé), un appel de même niveau ou de niveau inférieur n'est pas renvoyé pour cette patiente ; un niveau supérieur passe toujours immédiatement. Seul l'appel est retenu : le score, la pastille du dossier, la tournée et l'avertissement à l'écran suivent chaque mesure. Niveaux moyen et élevé : le médecin senior = chef du service du séjour + médecins de garde ; tous les médecins seulement en l'absence de garde. Une alerte ROUGE (indice de choc sévère, tension sévère) n'est jamais retenue. Quand un appel est retenu, la personne qui saisit lit « l'équipe soignante a déjà été prévenue il y a moins de 30 min … aucun nouvel appel n'a été envoyé ».
- **Statut** : provisoire, en attente de validation.

## Règles 2026-11-21 — PR #1276 (fusionnée sur develop 33d917eda)

- « Artéméther-luméfantrine, enfant : tranches de poids OMS par défaut » — 5–<15 kg 1 cp, 15–<25 kg 2 cp, 25–<35 kg 3 cp, ≥35 kg 4 cp de 20/120 mg, 2 fois par jour pendant 3 jours ; appliquées seulement à un produit clairement 20/120 quand le catalogue de l'établissement n'a aucune limite pédiatrique ; tout autre dosage, ou un dosage non reconnu, est mis en revue (« dosage non rapproché du tableau OMS ») ; dépassement = alerte critique avec motif ; dose conforme = revue ou exception urgente motivée tant que la valeur n'est pas approuvée localement ; < 5 kg = revue. Source : OMS, Guidelines for the treatment of malaria, 3e éd. 2015, section 4. Mise à jour : saisir les tranches approuvées sur l'article du catalogue (statut APPROVED). Statut : provisoire.
- (Opérationnel) Une ordonnance de sortie rédigée dans les 24 h après la sortie, avant toute nouvelle admission, rejoint le résumé de ce séjour ; une ordonnance arrêtée ou annulée en est retirée tant que le résumé n'est pas finalisé. Statut : provisoire.

## Règles 2026-11-22 — PR #1290 (fusionnée sur develop ca38794c0)

- « Imagerie pouvant révéler une grossesse » (PREGNANCY_REVEALING_IMAGING, version 2026-11-22.2) — les examens d'imagerie viennent des lignes IMAGERIE de la liste de prix, chacune avec un réglage « peut révéler une grossesse » (oui / non / non décidé), posé seulement par l'administrateur ou le radiologue de l'hôpital, tracé. Un examen marqué « oui », ou dont le nom, la région saisie, le compte rendu publié (titre, technique, résultats, conclusion, recommandations, comparaisons) ou une légende d'image nomme une grossesse, est caché aux proches (liste, images, facture). Une échographie ou un examen abdominal/pelvien d'une fille ou femme de 10 à 55 ans, ou d'âge inconnu (âge > 110 ans ou date future comptent comme inconnus), reste caché tant qu'il n'est pas déclaré « non ». Un « non » ne vaut que tant que le nom de l'examen de la commande est le libellé actuel de la ligne ; un renommage efface un « non » mais garde un « oui ». Une patiente de la tranche dont l'examen ne dit pas où il porte (liste bornée de parties du corps, ou mammographie/DEXA) est retenue. Parent vérifié d'un enfant sous l'âge de confidentialité (12 ans par défaut) : voit un examen non décidé, jamais un examen marqué ou nommé. Coût connu : un compte rendu qui dit « bassin », « pelvien » ou « annexes » cache l'examen aux proches de tout patient, hommes compris (une erreur ne fait que cacher). À valider : la tranche 10–55, les listes de mots (dont la nouvelle liste « où »). Statut : provisoire.
- « Renouvellements : alerte au seul prescripteur » (PRESCRIPTION_RENEWAL, version 2026-11-22.2) — l'alerte de renouvellement va au seul prescripteur tant qu'il est actif ET peut encore renouveler dans l'hôpital de l'ordonnance (une affectation retirée suffit à le remplacer) ; sinon au chef de son service (remplaçant désigné, lu dans l'hôpital où il agit). L'alerte ne nomme jamais le médicament. À valider : le chef de service comme remplaçant. Statut : provisoire.
- (Divulgation) La remise d'un résultat sensible (VIH) d'une mineure à un parent figure dans « Qui a consulté mon dossier » (qui, à qui, quand, jamais la valeur) ; le registre du personnel ne la montre qu'à l'hôpital qui l'a remise, sans destinataire ni catégorie.

## Règles 2026-11-23 — PR #1291 (fusionnée sur develop e5383140d)

- **TRANSFUSION_POSITIVE_ANTIBODY_SCREEN v2026-11-22.2** (zone LAB) — un dépistage d'agglutinines irrégulières (RAI) positif est porté par le patient dans tous les établissements, même s'il a refusé le partage du dossier ; la délivrance exige un cross-match compatible ; une délivrance en urgence ouvre une tâche de cross-match rétrospectif ; chaque dépistage positif est adressé au biologiste. Lecture du statut : médecins, chirurgiens, sages-femmes, infirmiers, laboratoire. **En attente** : signature clinique (biologiste, médecin chef) et juridique (DPO / CIL, loi 001-2021 à confirmer).
- **CRITICAL_RESULT_ESCALATION v2026-11-22.2** (zone LAB) — échelle prescripteur → remplaçant → chef de service par SMS envoyé après validation, un seul SMS à T0, incident ouvert au niveau ≥ 3 et clos avec une résolution. Les résultats déjà en attente avant le déploiement (V364) reçoivent un incident mais aucun SMS. **En attente**.
- **PARACETAMOL_LIMITS v2026-11-22.1** et **MATERNAL_EWS_ESCALATION v2026-11-22.1** — re-versionnées (source de l'alerte), valeurs inchangées ; la signature est rouverte.

## Motifs de dérogation clinique (décision du 27 novembre 2026)

**Provisoire, en attente d'approbation clinique.** Un motif de dérogation clinique doit contenir au moins 10 caractères hors espaces, après suppression des espaces en début et fin. Cette règle s'applique à la création et à la signature, aux autres motifs de dérogation clinique et à l'accès d'urgence. La dérogation d'imagerie conserve son choix de motif et ajoute une explication écrite. Ce seuil est une décision du responsable de la plateforme, sans source clinique établie, et doit être validé par un clinicien.
