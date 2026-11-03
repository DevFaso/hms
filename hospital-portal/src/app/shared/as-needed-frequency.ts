/**
 * QA round 30 (user decision 2026-11-02): an as-needed (PRN) order carries
 * the minimum interval between two doses, written by the prescriber.
 *
 * QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): the portal
 * no longer reads the frequency text to decide what is as-needed. Round 32
 * found ~45 wordings (SOS, p.r.n., sur demande, si precisa, when necessary
 * ...) that the shared word list missed. As-needed is the prescriber's
 * EXPLICIT "As needed (PRN)" tick on the prescription form and the order-set
 * item editor; the interval field shows from the tick. The server keeps the
 * text as a SAFETY NET only (`MarDoseSchedule.looksAsNeeded`): a conditional
 * frequency written without the tick is refused on `fieldErrors.asNeeded`,
 * and the shared server-field interceptor marks the box. The shared fixture
 * `as-needed-frequency.cases.json` holds every round-31 and round-32 wording
 * that net must catch (hospital-core MarDoseScheduleTest, PrnMinIntervalTest,
 * PrnAsNeededBackfillMigrationIT); `scripts/lib/as-needed-frequency.test.mjs`
 * keeps any frequency reader out of the portal.
 */

/** The longest minimum interval written, in hours (the server's `PrnMinInterval.MAX_HOURS`). */
export const PRN_MAX_INTERVAL_HOURS = 72;
