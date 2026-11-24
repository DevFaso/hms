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

export function presentationFindings(templates) {
  const findings = [];
  for (const { rel: file, text } of templates) {
    // Quantities beside an inflected unit must use the shared locale-aware formatter.
    const raw = /{{[^{}|]*(?:Quantity|quantity)[^{}|]*}}\s*{{[^{}]*(?:Unit|unit)[^{}]*}}/g;
    for (const match of text.matchAll(raw))
      findings.push(`${file}: unformatted counted unit: ${match[0]}`);
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
