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
  /**
   * Round 18 (QA D935): true on a card no override passes (a dose above the
   * absolute daily maximum); absent on every other card.
   */
  hardStop?: boolean | null;
  /** True on a critical card that the signature also asks a reason for; absent on every other card. */
  reasonAtSignature?: boolean | null;
  /**
   * CR-02: on a card signed only with the prescriber's explicit exception. A refused signature
   * sets it to the token the next attempt sends back, which ties the reason to these findings of
   * this version of the order.
   */
  exceptionKey?: string | null;
}
