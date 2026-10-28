package com.example.hms.service;

import com.example.hms.model.Encounter;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.security.CallerIdentity;

import java.util.UUID;

/**
 * Round 16 review: who treated a visit. The visit's clinician of record
 * ({@code encounter.staff}) is not always the doctor who saw the patient:
 * a nurse may open the visit under her own record, and a second doctor may
 * write the note. A clinician who wrote a note entry on the visit treated the
 * patient too. Used where a document is written in a treating clinician's
 * name (a medical certificate, QA D961) or belongs to them (a draft
 * discharge summary, QA D930).
 */
public final class TreatingClinician {

    private TreatingClinician() {
    }

    /** True when the signed-in user is the visit's clinician of record or wrote a note entry on it. */
    public static boolean isCaller(Encounter encounter, EncounterNoteRepository notes) {
        if (encounter == null) {
            return false;
        }
        if (CallerIdentity.isCaller(encounter.getStaff())) {
            return true;
        }
        UUID caller = CallerIdentity.currentUserId();
        if (caller == null || encounter.getId() == null || notes == null) {
            return false;
        }
        for (EncounterNote note : notes.findByEncounter_IdOrderByCreatedAtAscIdAsc(encounter.getId())) {
            if (note.getAuthor() != null && caller.equals(note.getAuthor().getId())) {
                return true;
            }
        }
        return false;
    }
}
