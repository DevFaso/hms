package com.example.hms.service;

import com.example.hms.payload.dto.bed.BedBoardDTO;

/**
 * The ward board and the census (Tier 2 item 31).
 *
 * <p>#433 gave the V25 ward/bed schema real writers, so the occupancy tiles
 * finally show true numbers. What they still cannot show is WHO — the board is
 * a grid of ward → room → bed with the occupant, their expected discharge, and
 * any isolation precaution in force, which together are what a charge nurse
 * needs to place the next admission.
 */
public interface BedBoardService {

    /** The whole board for the active hospital, census included. */
    BedBoardDTO getBoard();
}
