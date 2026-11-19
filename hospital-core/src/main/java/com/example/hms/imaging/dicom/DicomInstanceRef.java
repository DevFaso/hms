package com.example.hms.imaging.dicom;

/** One instance of a study, as a DICOMweb QIDO-RS answer lists it. */
public record DicomInstanceRef(
    String seriesInstanceUid,
    String sopInstanceUid,
    Integer seriesNumber,
    Integer instanceNumber,
    int numberOfFrames,
    String modality
) {
}
