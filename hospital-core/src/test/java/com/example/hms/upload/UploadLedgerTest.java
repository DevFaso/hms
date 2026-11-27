package com.example.hms.upload;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.UploadRecord;
import com.example.hms.repository.UploadRecordRepository;
import com.example.hms.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("A stored upload is recorded with its malware check, and served only when clean")
class UploadLedgerTest {

    private static final String KEY = "patient-documents/m16_ledger.pdf";

    @TempDir
    Path tempDir;

    private final UploadRecordRepository records = mock(UploadRecordRepository.class);
    private LocalFileStorage storage;
    private UploadScanner.Verdict verdict = UploadScanner.Verdict.CLEAN;
    private boolean scannerFails;
    private byte[] scanned;

    /** A scanner that reads the stored bytes and answers what the test set. */
    private final UploadScanner scanner = new UploadScanner() {
        @Override
        public String engine() {
            return "m16-test";
        }

        @Override
        public Verdict scan(Upload upload) throws IOException {
            if (scannerFails) {
                throw new IOException("scanner unreachable");
            }
            try (InputStream in = upload.content().open()) {
                scanned = in.readAllBytes();
            }
            return verdict;
        }
    };

    private UploadLedger ledger;

    @BeforeEach
    void setUp() throws IOException {
        storage = new LocalFileStorage(tempDir);
        storage.put(KEY, new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "application/pdf");
        when(records.save(any(UploadRecord.class))).thenAnswer(call -> call.getArgument(0));
        ledger = new UploadLedger(records, scanner, storage, mock(PlatformTransactionManager.class),
            java.time.Clock.systemDefaultZone());
    }

    private UploadLedger.StoredUpload upload() {
        return new UploadLedger.StoredUpload(KEY, UploadKind.PATIENT_DOCUMENT, "application/pdf", 3, "abc",
            UUID.randomUUID(), UUID.randomUUID());
    }

    private UploadRecord saved() {
        ArgumentCaptor<UploadRecord> row = ArgumentCaptor.forClass(UploadRecord.class);
        verify(records).save(row.capture());
        return row.getValue();
    }

    @Test
    @DisplayName("a clean file is recorded with its derived type, its sender and the scanner's name")
    void cleanIsRecorded() {
        UploadLedger.StoredUpload upload = upload();

        assertThat(ledger.record(upload)).isEqualTo(UploadScanStatus.CLEAN);

        assertThat(scanned).containsExactly(1, 2, 3);
        UploadRecord row = saved();
        assertThat(row.getStorageKey()).isEqualTo(KEY);
        assertThat(row.getContentType()).isEqualTo("application/pdf");
        assertThat(row.getUploadKind()).isEqualTo(UploadKind.PATIENT_DOCUMENT);
        assertThat(row.getSizeBytes()).isEqualTo(3);
        assertThat(row.getUploadedByUserId()).isEqualTo(upload.uploaderId());
        assertThat(row.getHospitalId()).isEqualTo(upload.hospitalId());
        assertThat(row.getScanStatus()).isEqualTo(UploadScanStatus.CLEAN);
        assertThat(row.getScanEngine()).isEqualTo("m16-test");
        assertThat(row.getScannedAt()).isNotNull();
    }

    @Test
    @DisplayName("a refused file is recorded as infected and stays in the store")
    void infectedIsKept() throws IOException {
        verdict = UploadScanner.Verdict.INFECTED;

        assertThat(ledger.record(upload())).isEqualTo(UploadScanStatus.INFECTED);

        assertThat(saved().getScanStatus()).isEqualTo(UploadScanStatus.INFECTED);
        assertThat(storage.get(KEY)).isPresent();
    }

    @Test
    @DisplayName("a scanner that cannot answer leaves the file pending, never clean")
    void aFailedScanIsPending() {
        scannerFails = true;
        assertThat(ledger.record(upload())).isEqualTo(UploadScanStatus.PENDING);
        UploadRecord row = saved();
        assertThat(row.getScanStatus()).isEqualTo(UploadScanStatus.PENDING);
        assertThat(row.getScannedAt()).isNull();
    }

    @Test
    @DisplayName("an unknown verdict leaves the file pending")
    void anUnknownVerdictIsPending() {
        verdict = UploadScanner.Verdict.UNKNOWN;
        assertThat(ledger.record(upload())).isEqualTo(UploadScanStatus.PENDING);
    }

    @Test
    @DisplayName("only a clean file, or one stored before the record existed, is served")
    void onlyCleanIsServed() {
        when(records.findByStorageKey("legacy/key")).thenReturn(Optional.empty());
        assertThatCode(() -> ledger.requireServable("legacy/key")).doesNotThrowAnyException();

        when(records.findByStorageKey(KEY)).thenReturn(Optional.of(row(UploadScanStatus.CLEAN)));
        assertThatCode(() -> ledger.requireServable(KEY)).doesNotThrowAnyException();

        when(records.findByStorageKey(KEY)).thenReturn(Optional.of(row(UploadScanStatus.PENDING)));
        assertThatThrownBy(() -> ledger.requireServable(KEY))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.upload.pendingCheck");

        when(records.findByStorageKey(KEY)).thenReturn(Optional.of(row(UploadScanStatus.INFECTED)));
        assertThatThrownBy(() -> ledger.requireServable(KEY))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "file.upload.blocked");
    }

    @Test
    @DisplayName("the rescan settles a pending file once the scanner answers, and leaves it pending until then")
    void rescanSettlesPending() {
        UploadRecord pending = row(UploadScanStatus.PENDING);
        when(records.findByScanStatusLeastRecentlyTried(any(), any())).thenReturn(List.of(pending));

        scannerFails = true;
        assertThat(ledger.rescanPending()).isZero();
        assertThat(pending.getScanStatus()).isEqualTo(UploadScanStatus.PENDING);
        // Still pending: touched, so the next sweep takes the newer files first.
        assertThat(pending.getUpdatedAt()).isNotNull();
        verify(records).save(pending);
        org.mockito.Mockito.clearInvocations(records);

        scannerFails = false;
        verdict = UploadScanner.Verdict.INFECTED;
        assertThat(ledger.rescanPending()).isEqualTo(1);
        assertThat(pending.getScanStatus()).isEqualTo(UploadScanStatus.INFECTED);
        assertThat(pending.getScannedAt()).isNotNull();
        verify(records).save(pending);
    }

    @Test
    @DisplayName("the rescan drops the record of a pending file that is no longer stored")
    void rescanDropsTheRecordOfARemovedFile() throws IOException {
        UploadRecord pending = row(UploadScanStatus.PENDING);
        pending.setId(UUID.randomUUID());
        when(records.findByScanStatusLeastRecentlyTried(any(), any())).thenReturn(List.of(pending));
        storage.delete(KEY);

        assertThat(ledger.rescanPending()).isEqualTo(1);
        verify(records).deleteById(pending.getId());
    }

    @Test
    @DisplayName("with no scanner plugged in every upload is clean, under the engine name none")
    void theDefaultScannerAnswersClean() {
        UploadLedger noScanner = new UploadLedger(records, new NoOpUploadScanner(), storage,
            mock(PlatformTransactionManager.class), java.time.Clock.systemDefaultZone());

        assertThat(noScanner.record(upload())).isEqualTo(UploadScanStatus.CLEAN);
        assertThat(saved().getScanEngine()).isEqualTo("none");
    }

    private static UploadRecord row(UploadScanStatus status) {
        return UploadRecord.builder().storageKey(KEY).uploadKind(UploadKind.PATIENT_DOCUMENT)
            .contentType("application/pdf").sizeBytes(3).scanStatus(status).build();
    }
}
