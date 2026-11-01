// i18n review 2026-11-01 guard: French and Spanish labels are written in
// sentence case. 232 of the review's 330 typography findings were English
// title case copied into a translation ("Mode de Paiement", "Nouvelle
// Facture", "Fecha de Nacimiento", "Oui, Annuler"); the class was drained to
// zero (790 values) and this test keeps it there. A UI label named in running
// text goes in guillemets ("utilisez « Désactiver »") and keeps its capital.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { titleCasedWords } from './sentence-case-scan.mjs';

const i18nDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'assets', 'i18n');

function offenders(lang) {
  const out = [];
  (function walk(node, path) {
    for (const [k, v] of Object.entries(node)) {
      if (v && typeof v === 'object') walk(v, `${path}${k}.`);
      else if (typeof v === 'string') {
        const words = titleCasedWords(v);
        if (words.length) out.push(`${path}${k} = ${v}  [${words.map((w) => w.word).join(', ')}]`);
      }
    }
  })(JSON.parse(readFileSync(join(i18nDir, `${lang}.json`), 'utf8')), '');
  return out;
}

for (const lang of ['fr', 'es']) {
  test(`no ${lang} label copies English title case`, () => {
    assert.deepEqual(offenders(lang), []);
  });
}

test('the scan sees the shapes the review found', () => {
  const words = (v) => titleCasedWords(v).map((w) => w.word);
  assert.deepEqual(words('Mode de Paiement'), ['Paiement']);
  assert.deepEqual(words("Type d'Événement"), ['Événement']);
  assert.deepEqual(words('Oui, Annuler'), ['Annuler']);
  assert.deepEqual(words('Fecha de Nacimiento'), ['Nacimiento']);
  assert.deepEqual(words('Commandes Labo Aujourd’hui'), ['Labo', 'Aujourd']);
  assert.deepEqual(words('{{count}} échecs - réparez le récepteur puis Reprendre.'), ['Reprendre']);
  assert.deepEqual(words('Créez votre compte Super Administrateur'), ['Super', 'Administrateur']);
});

test('sentence starts, quoted labels, identifiers, examples and proper nouns are not title case', () => {
  const clean = [
    'Mode de paiement',
    'Statut : Confirmé. Suite du texte',
    'Échec du chargement. Réessayez ? Oui',
    'Pour terminer un séjour, utilisez « Désactiver ».',
    'Cree la derivación como «Rutinaria» por ahora.',
    'Seul du O Rh négatif peut être délivré.',
    'Saisissez la SpO2, le code RxNorm ou le MenAfriVac',
    'Définit la surcharge globale de FeatureFlag',
    'Le fichier ne doit pas dépasser 10 Mo.',
    'ex. Or, Argent',
    'p. ej., Cardiología',
    '← Retour',
    'Burkina Faso, Côte d’Ivoire, New York',
    'Google Authenticator, Authy',
    'Graphiques de contrôle Levey-Jennings',
    'Paiement par Mobile Money',
    'Score d’Apgar à 5 minutes',
    '{{name}} Patient',
    'Bienvenue (Nouveau compte)',
  ];
  for (const v of clean) assert.deepEqual(titleCasedWords(v), [], v);
});
