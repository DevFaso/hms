/** Display contracts from r72/r73; no baseline and no exclusions for user-visible prose. */
export function copiedTranslations(
  source,
  target,
  prefix = '',
  allowed = new Set(['CHANNEL.SMS']),
) {
  const findings = [];
  for (const [key, value] of Object.entries(source)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object') {
      findings.push(...copiedTranslations(value, target?.[key] ?? {}, path, allowed));
    } else if (typeof target?.[key] !== 'string' || (!allowed.has(path) && value === target[key])) {
      findings.push(path);
    }
  }
  return findings;
}

export function deliveryTranslationFindings(en, translated) {
  return [
    ...copiedTranslations(en.PATIENT_NOTIFICATIONS, translated.PATIENT_NOTIFICATIONS).map(
      (key) => `es.PATIENT_NOTIFICATIONS.${key}: missing translation or copied English`,
    ),
    ...copiedTranslations(
      { 'NAV.PATIENT_NOTIFICATIONS': en.NAV?.PATIENT_NOTIFICATIONS },
      { 'NAV.PATIENT_NOTIFICATIONS': translated.NAV?.PATIENT_NOTIFICATIONS },
    ).map((key) => `es.${key}: missing translation or copied English`),
  ];
}

/** These shared categories control every appointment/follow-up and pharmacy notice. */
export function consentScopeFindings(catalog, language) {
  const scope = { en: 'all messages', fr: 'tous les messages', es: 'todos los mensajes' }[language];
  return ['TYPE_APPOINTMENT_REMINDER', 'TYPE_MEDICATION_REFILL']
    .filter((key) => !catalog.NOTIFICATIONS?.[key]?.toLocaleLowerCase(language).includes(scope))
    .map(
      (key) => `${language}.NOTIFICATIONS.${key}: label hides part of the shared sender category`,
    );
}

export function presentationFindings(templates) {
  const findings = [];
  for (const { rel: file, text } of templates) {
    // Quantities beside an inflected unit must use the shared locale-aware formatter.
    const raw = /{{[^{}|]*(?:Quantity|quantity)[^{}|]*}}\s*{{[^{}]*(?:Unit|unit)[^{}]*}}/g;
    for (const match of text.matchAll(raw))
      findings.push(`${file}: unformatted counted unit: ${match[0]}`);
    for (const match of text.matchAll(
      /{{\s*\w+\.itemCategory\s*\|\s*enumLabel\s*:\s*'invoiceItemCategory'\s*}}/g,
    ))
      findings.push(
        `${file}: invoice category ignores the more specific stored tariff category: ${match[0]}`,
      );
    for (const match of text.matchAll(
      /'PATIENTS\.MATCHING_RECORDS'[\s\S]*?translate\s*:\s*{([^}]+)}/g,
    )) {
      if (
        !match[1].includes('loading()') ||
        !match[1].includes('loadError()') ||
        !match[1].includes('—')
      ) {
        findings.push(`${file}: matching-record count does not distinguish unknown data from zero`);
      }
    }
  }
  return findings;
}
