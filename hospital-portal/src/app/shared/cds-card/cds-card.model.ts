/**
 * Wire shape of a CDS Hooks 1.0 card returned by the HMS rule engine
 * (see `hospital-core/src/main/java/com/example/hms/cdshooks/dto/CdsHookDtos.java`).
 *
 * The backend serialises the {@code Indicator} enum via {@code @JsonValue}
 * to lowercase strings, so consumers should treat the `indicator` field
 * as the literal lowercase form.
 */
export type CdsIndicator = 'info' | 'warning' | 'critical';

export interface CdsSource {
  label: string;
  url?: string | null;
  icon?: string | null;
}

export interface CdsCard {
  summary: string;
  detail?: string | null;
  indicator: CdsIndicator;
  source: CdsSource;
  uuid?: string | null;
}
