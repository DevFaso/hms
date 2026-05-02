package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingReport;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImagingReportMapper — PACS viewer URL resolution (gap #23)")
class ImagingReportMapperPacsTest {

    private final ImagingReportMapper mapper = new ImagingReportMapper();

    private ImagingReport buildReport(Hospital hospital, String studyUid, String accession, String explicit) {
        ImagingReport r = new ImagingReport();
        r.setHospital(hospital);
        r.setStudyInstanceUid(studyUid);
        r.setAccessionNumber(accession);
        r.setPacsViewerUrl(explicit);
        return r;
    }

    @Test
    @DisplayName("explicit pacs URL on report wins over the template")
    void explicitWins() {
        Hospital hosp = new Hospital();
        hosp.setPacsViewerUrlTemplate("https://orthanc/?studyUid={studyInstanceUid}");
        ImagingReport r = buildReport(hosp, "1.2.3", null, "https://override.example/study/1.2.3");

        ImagingReportResponseDTO dto = mapper.toResponseDTO(r);

        assertThat(dto.getPacsViewerUrl()).isEqualTo("https://override.example/study/1.2.3");
    }

    @Test
    @DisplayName("renders template using studyInstanceUid when no explicit URL")
    void templateRenderedFromStudyUid() {
        Hospital hosp = new Hospital();
        hosp.setPacsViewerUrlTemplate("https://orthanc.local/viewer.html?studyUid={studyInstanceUid}");
        ImagingReport r = buildReport(hosp, "1.2.3.4.5", null, null);

        ImagingReportResponseDTO dto = mapper.toResponseDTO(r);

        assertThat(dto.getPacsViewerUrl())
                .isEqualTo("https://orthanc.local/viewer.html?studyUid=1.2.3.4.5");
    }

    @Test
    @DisplayName("renders template using accessionNumber placeholder")
    void templateRendersAccessionNumber() {
        Hospital hosp = new Hospital();
        hosp.setPacsViewerUrlTemplate("https://pacs/?acc={accessionNumber}");
        ImagingReport r = buildReport(hosp, null, "ACC-9001", null);

        ImagingReportResponseDTO dto = mapper.toResponseDTO(r);

        assertThat(dto.getPacsViewerUrl()).isEqualTo("https://pacs/?acc=ACC-9001");
    }

    @Test
    @DisplayName("returns null when no template, no explicit URL")
    void noTemplate_noExplicit_returnsNull() {
        Hospital hosp = new Hospital();
        ImagingReport r = buildReport(hosp, "1.2.3", null, null);

        assertThat(mapper.toResponseDTO(r).getPacsViewerUrl()).isNull();
    }

    @Test
    @DisplayName("returns null when template is set but report has no UIDs")
    void templateButNoUids_returnsNull() {
        Hospital hosp = new Hospital();
        hosp.setPacsViewerUrlTemplate("https://orthanc/?studyUid={studyInstanceUid}");
        ImagingReport r = buildReport(hosp, null, null, null);

        assertThat(mapper.toResponseDTO(r).getPacsViewerUrl()).isNull();
    }
}
