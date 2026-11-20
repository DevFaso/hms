/**
 * Policy 2026-11-20 (provisional, pending a named obstetric clinician's sign-off): the "additional
 * concerns" of the national maternal early warning score a recorder may tick with a vital set (the
 * server's MaternalConcern). They raise the level of concern of a pregnant or postpartum woman on
 * the server; they are never points, and the portal holds no band or threshold of its own.
 *
 * A lowered consciousness is not offered here: the consciousness field of the same form records it,
 * and the server reads it from there (oxygen likewise, although an added therapy can be ticked).
 */
export const MATERNAL_CONCERNS = [
  'STAFF_CONCERNED',
  'WOMAN_OR_FAMILY_CONCERNED',
  'ADDITIONAL_THERAPY',
  'INCREASED_PAIN',
  'VAGINAL_BLEEDING',
  'REDUCED_URINE_OUTPUT',
] as const;

export type MaternalConcern = (typeof MATERNAL_CONCERNS)[number];
