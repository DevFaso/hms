package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListImportResult;
import com.example.hms.enums.PriceListImportRowOutcome;
import com.example.hms.enums.PriceListImportStatus;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PriceListImport;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PriceListImportReportDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListImportRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Billing-money plan PR 4 (V304, scorecard M1): the price list from and to a spreadsheet.
 *
 * <p><b>Import.</b> A dry run ("Vérifier le fichier") checks every row against the acting hospital's grid and lists
 * what each row would do (new item, new price, unchanged) or why it is refused, with its line, its column and the
 * reason in the reader's language; it writes nothing to the price list (it leaves a DRY_RUN trace row). An apply
 * re-checks every row under the lock of every item the file names, then writes everything in one transaction, or
 * nothing when any row is refused. A row whose price is already the one in force on its date is unchanged and
 * writes nothing; a changed price is a new version from the row's date, with "Import &lt;file name&gt;" as its
 * reason, through the same rules as the screen ({@link PriceListService#planVersion}). The file's SHA-256 is kept:
 * the same file applied again at the same hospital is reported "déjà importé" and writes nothing.
 *
 * <p><b>What an import never does.</b> It never edits an item's label, category or link (a different one is refused,
 * to be changed on the screen), never touches a deactivated item, and never writes a price in the past.
 *
 * <p><b>Who.</b> Import: HOSPITAL_ADMIN or ACCOUNTANT at the acting hospital (owner decision D-1), checked before the
 * file is read. Export: whoever reads the price list, at one hospital.
 *
 * <p><b>Audit.</b> One PRICE_LIST_IMPORTED row per applied file, after commit, with the import id and the counts only;
 * every version it wrote carries the import's id.
 */
@Slf4j
@Service
public class PriceListImportService {

    static final String AUDIT_ENTITY = "PRICE_LIST_IMPORT";
    static final String FALLBACK_FILE_NAME = "grille.csv";
    static final int FILE_NAME_MAX = 255;
    private static final String REASON_PREFIX = "Import ";

    private final PriceListService priceList;
    private final PriceListItemRepository itemRepository;
    private final PriceListPriceRepository priceRepository;
    private final PriceListImportRepository importRepository;
    private final PriceListLinks links;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final AuditEventLogService auditEventLogService;

    @SuppressWarnings("java:S107") // one collaborator per concern; a parameter object would only hide them
    public PriceListImportService(PriceListService priceList,
                                  PriceListItemRepository itemRepository,
                                  PriceListPriceRepository priceRepository,
                                  PriceListImportRepository importRepository,
                                  PriceListLinks links,
                                  LabTestDefinitionRepository labTestDefinitionRepository,
                                  AuditEventLogService auditEventLogService) {
        this.priceList = priceList;
        this.itemRepository = itemRepository;
        this.priceRepository = priceRepository;
        this.importRepository = importRepository;
        this.links = links;
        this.labTestDefinitionRepository = labTestDefinitionRepository;
        this.auditEventLogService = auditEventLogService;
    }

    // ───────────────────────────── import ─────────────────────────────

    /**
     * Checks ({@code dryRun}) or applies a price-list CSV file at the acting hospital. {@code replaceScheduled}
     * confirms, for the whole file, that a price already planned on a row's exact date may be replaced (the screen's
     * confirmation); a price already in force or already invoiced is never replaced.
     */
    @Transactional
    public PriceListImportReportDTO importFile(byte[] bytes, String originalFileName, boolean dryRun,
                                               boolean replaceScheduled) {
        PriceListService.Maintainer actor = priceList.requireMaintainer();
        String fileName = fileNameOf(originalFileName);
        PriceListCsv.Parsed parsed = PriceListCsv.parse(bytes);
        String sha256 = sha256(bytes);
        Optional<PriceListImport> earlier = importRepository.findFirstByHospitalIdAndSha256AndStatus(
            actor.hospitalId(), sha256, PriceListImportStatus.APPLIED);
        if (!dryRun && earlier.isPresent()) {
            return alreadyImported(earlier.get(), fileName, sha256);
        }

        LocalDate today = priceList.today();
        List<Planned> planned = evaluate(parsed.rows(), actor.hospitalId(), today, replaceScheduled, !dryRun);
        Counts counts = Counts.of(planned);
        PriceListImportReportDTO.PriceListImportReportDTOBuilder report = PriceListImportReportDTO.builder()
            .fileName(fileName)
            .sha256(sha256)
            .alreadyImported(earlier.isPresent())
            .alreadyImportedAt(earlier.map(PriceListImport::getCreatedAt).orElse(null))
            .totalRows(planned.size())
            .newItems(counts.newItems())
            .newPrices(counts.newPrices())
            .unchanged(counts.unchanged())
            .refused(counts.refused())
            .rows(planned.stream().map(Planned::row).toList());

        if (dryRun) {
            PriceListImport trace = importRepository.save(trace(actor, fileName, sha256,
                PriceListImportStatus.DRY_RUN, planned.size(), counts));
            return report.importId(trace.getId()).result(PriceListImportResult.DRY_RUN).build();
        }
        if (counts.refused() > 0) {
            // All or nothing: not one row is written, and no trace either.
            return report.result(PriceListImportResult.REFUSED).build();
        }

        PriceListImport trace;
        try {
            trace = importRepository.saveAndFlush(trace(actor, fileName, sha256, PriceListImportStatus.APPLIED,
                planned.size(), counts));
        } catch (DataIntegrityViolationException sameFileConcurrently) {
            // Another apply of this very file at this hospital committed first (V304 partial UNIQUE).
            throw new BusinessException("billing.priceList.import.appliedConcurrently");
        }
        String reason = (REASON_PREFIX + fileName).substring(0,
            Math.min(PriceListService.REASON_MAX, REASON_PREFIX.length() + fileName.length()));
        for (Planned row : planned) {
            switch (row.row().getOutcome()) {
                case NEW_ITEM -> priceList.insertItem(actor, row.newItem(), row.price(), row.from(), reason,
                    trace.getId());
                case NEW_PRICE -> priceList.insertVersion(row.item(), actor, row.versions(), row.plan(), row.price(),
                    row.from(), reason, trace.getId());
                default -> {
                    // UNCHANGED writes nothing; REFUSED cannot reach here.
                }
            }
        }
        auditAfterCommit(actor, trace.getId(), counts);
        return report.importId(trace.getId()).result(PriceListImportResult.APPLIED).build();
    }

    /** What one row will do, with everything its write needs. */
    private record Planned(PriceListImportReportDTO.Row row, PriceListService.NewItem newItem, PriceListItem item,
                           List<PriceListPrice> versions, PriceVersionRules.Plan plan, BigDecimal price,
                           LocalDate from) {
        static Planned refused(PriceListImportReportDTO.Row row) {
            return new Planned(row, null, null, null, null, null, null);
        }
    }

    private record Counts(int newItems, int newPrices, int unchanged, int refused) {
        static Counts of(List<Planned> planned) {
            Map<PriceListImportRowOutcome, Long> by = planned.stream()
                .collect(Collectors.groupingBy(p -> p.row().getOutcome(), Collectors.counting()));
            return new Counts(by.getOrDefault(PriceListImportRowOutcome.NEW_ITEM, 0L).intValue(),
                by.getOrDefault(PriceListImportRowOutcome.NEW_PRICE, 0L).intValue(),
                by.getOrDefault(PriceListImportRowOutcome.UNCHANGED, 0L).intValue(),
                by.getOrDefault(PriceListImportRowOutcome.REFUSED, 0L).intValue());
        }
    }

    /** The link a row resolved to, or why it could not. */
    private record ResolvedLink(PriceListLinkType type, String key, FieldBusinessException refusal) {
    }

    /** Everything a row is checked against: the hospital's grid, read once (and locked for an apply). */
    private final class Grid {
        final UUID hospitalId;
        final LocalDate today;
        final Map<String, PriceListItem> byCode = new HashMap<>();
        final Map<UUID, List<PriceListPrice>> versions = new HashMap<>();
        /** "TYPE|key" of every active linked item, to the item's code. */
        final Map<String, String> activeLinks = new HashMap<>();
        final Map<String, ResolvedLink> resolved = new HashMap<>();
        /** The first line each code and each link appears on in the file. */
        final Map<String, Integer> seenCodes = new HashMap<>();
        final Map<String, Integer> seenLinks = new HashMap<>();

        Grid(UUID hospitalId, LocalDate today, Set<String> fileCodes, boolean lock) {
            this.hospitalId = hospitalId;
            this.today = today;
            if (lock) {
                // The items this file names, locked first (in code order, as every import takes them), so what is
                // read next is what the apply writes against.
                List<String> codes = fileCodes.stream().filter(code -> !code.isEmpty()).sorted().toList();
                for (List<String> chunk : chunksOf(codes)) {
                    itemRepository.lockByHospitalIdAndCodes(hospitalId, chunk);
                }
            }
            for (PriceListItem item : itemRepository.findByHospitalIdOrderByCodeAsc(hospitalId)) {
                byCode.put(item.getCode(), item);
                if (item.isActive() && item.getLinkType() != null) {
                    activeLinks.put(linkId(item.getLinkType(), item.getLinkKey()), item.getCode());
                }
            }
            List<UUID> ids = byCode.values().stream()
                .filter(item -> fileCodes.contains(item.getCode()))
                .map(PriceListItem::getId)
                .toList();
            for (List<UUID> chunk : PriceListService.chunksOf(ids)) {
                for (PriceListPrice price : priceRepository.findByItemIds(chunk)) {
                    versions.computeIfAbsent(price.getItem().getId(), id -> new ArrayList<>()).add(price);
                }
            }
        }

        ResolvedLink link(PriceListLinkType type, String rawKey) {
            return resolved.computeIfAbsent(type + "|" + rawKey, id -> {
                try {
                    return new ResolvedLink(type, links.normalise(hospitalId, type, rawKey), null);
                } catch (FieldBusinessException refusal) {
                    return new ResolvedLink(type, null, refusal);
                }
            });
        }
    }

    private static List<List<String>> chunksOf(List<String> codes) {
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < codes.size(); start += PriceListService.IN_CHUNK) {
            chunks.add(codes.subList(start, Math.min(codes.size(), start + PriceListService.IN_CHUNK)));
        }
        return chunks;
    }

    private static String linkId(PriceListLinkType type, String key) {
        return type.name() + "|" + key;
    }

    private List<Planned> evaluate(List<PriceListCsv.Row> rows, UUID hospitalId, LocalDate today,
                                   boolean replaceScheduled, boolean lock) {
        Set<String> fileCodes = new LinkedHashSet<>();
        for (PriceListCsv.Row row : rows) {
            fileCodes.add(row.get(PriceListCsv.COL_CODE).toUpperCase(Locale.ROOT));
        }
        Grid grid = new Grid(hospitalId, today, fileCodes, lock);
        List<Planned> planned = new ArrayList<>(rows.size());
        for (PriceListCsv.Row row : rows) {
            planned.add(evaluate(row, grid, replaceScheduled));
        }
        return planned;
    }

    /** One row: every reason it is refused, or what it will write. */
    @SuppressWarnings("java:S3776") // one check per column, in column order, reads better than split helpers
    private Planned evaluate(PriceListCsv.Row csv, Grid grid, boolean replaceScheduled) {
        List<PriceListImportReportDTO.Refusal> refusals = new ArrayList<>();
        String code = csv.get(PriceListCsv.COL_CODE).toUpperCase(Locale.ROOT);
        String label = csv.get(PriceListCsv.COL_LABEL);
        PriceListImportReportDTO.Row row = PriceListImportReportDTO.Row.builder()
            .line(csv.line()).code(code).label(label).refusals(refusals).build();

        // CSV injection: a cell a spreadsheet would run is refused, whatever the column (the price is checked as a
        // number below, which a formula never is).
        for (String column : PriceListCsv.HEADER) {
            if (!PriceListCsv.COL_PRICE.equals(column) && PriceListCsv.isFormula(csv.get(column))) {
                refuse(refusals, column, "billing.priceList.import.formula");
            }
        }
        if (csv.extraCells()) {
            refuse(refusals, null, "billing.priceList.import.extraCells");
        }
        boolean codeUsable = !PriceListCsv.isFormula(code);
        if (codeUsable && code.isEmpty()) {
            refuse(refusals, PriceListCsv.COL_CODE, "billing.priceList.codeRequired");
            codeUsable = false;
        } else if (codeUsable && (code.length() > PriceListService.CODE_MAX
                || !PriceListService.CODE_FORMAT.matcher(code).matches())) {
            refuse(refusals, PriceListCsv.COL_CODE, "billing.priceList.codeFormat", PriceListService.CODE_MAX);
            codeUsable = false;
        }
        if (codeUsable) {
            Integer first = grid.seenCodes.putIfAbsent(code, csv.line());
            if (first != null) {
                refuse(refusals, PriceListCsv.COL_CODE, "billing.priceList.import.duplicateCode", code,
                    String.valueOf(first));
            }
        }
        if (!PriceListCsv.isFormula(label)) {
            if (label.isEmpty()) {
                refuse(refusals, PriceListCsv.COL_LABEL, "billing.priceList.labelRequired");
            } else if (label.length() > PriceListService.LABEL_MAX) {
                refuse(refusals, PriceListCsv.COL_LABEL, "billing.priceList.labelTooLong", PriceListService.LABEL_MAX);
            }
        }
        PriceListCategory category = category(csv.get(PriceListCsv.COL_CATEGORY), refusals);
        BigDecimal price = null;
        try {
            price = PriceListCsv.parsePrice(csv.get(PriceListCsv.COL_PRICE));
            PriceVersionRules.requireValidPrice(price);
        } catch (FieldBusinessException refusal) {
            refusals.add(refusal(PriceListCsv.COL_PRICE, refusal));
            price = null;
        }
        row.setPrice(price);
        LocalDate date = null;
        try {
            date = PriceListCsv.parseDate(csv.get(PriceListCsv.COL_DATE));
        } catch (FieldBusinessException refusal) {
            refusals.add(refusal(PriceListCsv.COL_DATE, refusal));
        }
        LocalDate from = date != null ? date : grid.today;
        row.setEffectiveDate(from);
        ResolvedLink link = link(csv, grid, refusals);

        if (!refusals.isEmpty()) {
            return refused(row);
        }
        PriceListItem existing = grid.byCode.get(code);
        return existing == null
            ? newItem(row, grid, code, label, category, link, price, from)
            : newPrice(row, grid, existing, label, category, link, price, from, replaceScheduled);
    }

    private Planned newItem(PriceListImportReportDTO.Row row, Grid grid, String code, String label,
                            PriceListCategory category, ResolvedLink link, BigDecimal price, LocalDate from) {
        List<PriceListImportReportDTO.Refusal> refusals = row.getRefusals();
        if (from.isBefore(grid.today)) {
            refuse(refusals, PriceListCsv.COL_DATE, "billing.priceList.dateInPast");
        }
        if (link != null) {
            String holder = grid.activeLinks.get(linkId(link.type(), link.key()));
            if (holder != null) {
                refuse(refusals, PriceListCsv.COL_LINK_KEY, "billing.priceList.linkTaken", link.key(), holder);
            }
        }
        if (!refusals.isEmpty()) {
            return refused(row);
        }
        row.setOutcome(PriceListImportRowOutcome.NEW_ITEM);
        return new Planned(row, new PriceListService.NewItem(code, label, category,
            link == null ? null : link.type(), link == null ? null : link.key()), null, null, null, price, from);
    }

    @SuppressWarnings("java:S107") // the row's checked cells, one per column
    private Planned newPrice(PriceListImportReportDTO.Row row, Grid grid, PriceListItem item, String label,
                             PriceListCategory category, ResolvedLink link, BigDecimal price, LocalDate from,
                             boolean replaceScheduled) {
        List<PriceListImportReportDTO.Refusal> refusals = row.getRefusals();
        if (!item.isActive()) {
            refuse(refusals, PriceListCsv.COL_CODE, "billing.priceList.inactive");
            return refused(row);
        }
        // The import changes prices only: the item's label, category and link are changed on the screen.
        if (!item.getLabel().equals(label)) {
            refuse(refusals, PriceListCsv.COL_LABEL, "billing.priceList.import.labelDiffers", item.getLabel());
        }
        if (item.getCategory() != category) {
            refuse(refusals, PriceListCsv.COL_CATEGORY, "billing.priceList.import.categoryDiffers",
                item.getCategory().name());
        }
        PriceListLinkType linkType = link == null ? null : link.type();
        String linkKey = link == null ? null : link.key();
        if (item.getLinkType() != linkType || !Objects.equals(item.getLinkKey(), linkKey)) {
            refuse(refusals, PriceListCsv.COL_LINK_KEY, "billing.priceList.import.linkDiffers");
        }
        if (!refusals.isEmpty()) {
            return refused(row);
        }
        List<PriceListPrice> versions = grid.versions.getOrDefault(item.getId(), new ArrayList<>());
        Optional<PriceListPrice> covering = versions.stream().filter(v -> v.coversDay(from)).findFirst();
        covering.ifPresent(v -> row.setPreviousPrice(v.getUnitPrice()));
        if (covering.isPresent() && covering.get().getUnitPrice().compareTo(price) == 0) {
            row.setOutcome(PriceListImportRowOutcome.UNCHANGED);
            return new Planned(row, null, item, versions, null, price, from);
        }
        if (from.isBefore(grid.today)) {
            refuse(refusals, PriceListCsv.COL_DATE, "billing.priceList.dateInPast");
            return refused(row);
        }
        PriceVersionRules.Plan plan;
        try {
            plan = priceList.planVersion(versions, grid.today, from, replaceScheduled);
        } catch (FieldBusinessException refusal) {
            refusals.add(planRefusal(refusal));
            return refused(row);
        }
        row.setOutcome(PriceListImportRowOutcome.NEW_PRICE);
        return new Planned(row, null, item, versions, plan, price, from);
    }

    private static Planned refused(PriceListImportReportDTO.Row row) {
        row.setOutcome(PriceListImportRowOutcome.REFUSED);
        return Planned.refused(row);
    }

    private static PriceListCategory category(String raw, List<PriceListImportReportDTO.Refusal> refusals) {
        if (PriceListCsv.isFormula(raw)) {
            return null;
        }
        if (raw.isEmpty()) {
            refuse(refusals, PriceListCsv.COL_CATEGORY, "billing.priceList.categoryRequired");
            return null;
        }
        try {
            return PriceListCategory.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            refuse(refusals, PriceListCsv.COL_CATEGORY, "billing.priceList.import.categoryUnknown", raw,
                Arrays.stream(PriceListCategory.values()).map(Enum::name).collect(Collectors.joining(", ")));
            return null;
        }
    }

    /** The row's link, checked as the screen checks it; null for no link (also when refused). */
    private static ResolvedLink link(PriceListCsv.Row csv, Grid grid, List<PriceListImportReportDTO.Refusal> refusals) {
        String rawType = csv.get(PriceListCsv.COL_LINK_TYPE);
        String rawKey = csv.get(PriceListCsv.COL_LINK_KEY);
        if (PriceListCsv.isFormula(rawType) || PriceListCsv.isFormula(rawKey)) {
            return null;
        }
        if (rawType.isEmpty() && rawKey.isEmpty()) {
            return null;
        }
        PriceListLinkType type = null;
        if (!rawType.isEmpty()) {
            try {
                type = PriceListLinkType.valueOf(rawType.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                refuse(refusals, PriceListCsv.COL_LINK_TYPE, "billing.priceList.import.linkTypeUnknown", rawType,
                    Arrays.stream(PriceListLinkType.values()).map(Enum::name).collect(Collectors.joining(", ")));
                return null;
            }
        }
        if (type == null) {
            refuse(refusals, PriceListCsv.COL_LINK_TYPE, "billing.priceList.linkTypeRequired");
            return null;
        }
        ResolvedLink link = grid.link(type, rawKey);
        if (link.refusal() != null) {
            refusals.add(refusal(PriceListLinks.FIELD_LINK_TYPE.equals(link.refusal().getField())
                ? PriceListCsv.COL_LINK_TYPE : PriceListCsv.COL_LINK_KEY, link.refusal()));
            return null;
        }
        Integer first = grid.seenLinks.putIfAbsent(linkId(link.type(), link.key()), csv.line());
        if (first != null) {
            refuse(refusals, PriceListCsv.COL_LINK_KEY, "billing.priceList.import.duplicateLink", rawKey,
                String.valueOf(first));
            return null;
        }
        return link;
    }

    /** A refusal of the version rules, said for a file (no confirmation dialog: the "replace" option instead). */
    private static PriceListImportReportDTO.Refusal planRefusal(FieldBusinessException refusal) {
        // The screen asks to confirm; a file says to tick the "replace" option instead (same arguments).
        String message = null;
        if ("billing.priceList.scheduledExists".equals(refusal.getMessageKey())) {
            message = MessageUtil.resolve("billing.priceList.import.scheduledExists", refusal.getArgs());
        } else if ("billing.priceList.correctToday".equals(refusal.getMessageKey())) {
            message = MessageUtil.resolve("billing.priceList.import.correctToday", refusal.getArgs());
        }
        if (message != null) {
            return PriceListImportReportDTO.Refusal.builder().column(PriceListCsv.COL_DATE).message(message).build();
        }
        return refusal(PriceListCsv.COL_DATE, refusal);
    }

    private static PriceListImportReportDTO.Refusal refusal(String column, FieldBusinessException refusal) {
        return PriceListImportReportDTO.Refusal.builder().column(column).message(refusal.getMessage()).build();
    }

    private static void refuse(List<PriceListImportReportDTO.Refusal> refusals, String column, String key,
                               Object... args) {
        refusals.add(PriceListImportReportDTO.Refusal.builder()
            .column(column)
            .message(MessageUtil.resolve(key, args))
            .build());
    }

    private PriceListImportReportDTO alreadyImported(PriceListImport earlier, String fileName, String sha256) {
        return PriceListImportReportDTO.builder()
            .importId(earlier.getId())
            .fileName(fileName)
            .sha256(sha256)
            .result(PriceListImportResult.ALREADY_IMPORTED)
            .alreadyImported(true)
            .alreadyImportedAt(earlier.getCreatedAt())
            .totalRows(earlier.getTotalRows())
            .rows(List.of())
            .build();
    }

    private static PriceListImport trace(PriceListService.Maintainer actor, String fileName, String sha256,
                                         PriceListImportStatus status, int total, Counts counts) {
        return PriceListImport.builder()
            .hospitalId(actor.hospitalId())
            .fileName(fileName)
            .sha256(sha256)
            .status(status)
            .totalRows(total)
            .createdCount(counts.newItems())
            .newPriceCount(counts.newPrices())
            .unchangedCount(counts.unchanged())
            .refusedCount(counts.refused())
            .importedBy(actor.userId())
            .build();
    }

    /** The uploaded name without any path, control characters or excess length; a default when blank. */
    static String fileNameOf(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.length() > FILE_NAME_MAX) {
            name = name.substring(0, FILE_NAME_MAX);
        }
        return name.isEmpty() ? FALLBACK_FILE_NAME : name;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException cannotHappen) {
            // SHA-256 is part of every JRE.
            throw new IllegalStateException(cannotHappen);
        }
    }

    private void auditAfterCommit(PriceListService.Maintainer actor, UUID importId, Counts counts) {
        String importIdText = String.valueOf(importId);
        UUID userId = actor.userId();
        UUID hospitalId = actor.hospitalId();
        String description = "Price list import " + importIdText + " applied: " + counts.newItems() + " new items, "
            + counts.newPrices() + " new prices, " + counts.unchanged() + " unchanged";
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.PRICE_LIST_IMPORTED)
                    .status(AuditStatus.SUCCESS)
                    .userId(userId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(importIdText)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Price list import audit not written for import {}: {}", importIdText,
                    ex.getClass().getSimpleName());
            }
        });
    }

    // ───────────────────────────── export ─────────────────────────────

    /**
     * The acting hospital's active items in the import's format, each with the price in force today (else the next
     * planned one) and its start, so the file re-imports as "0 nouvelles, N inchangées". A lab-test link is written
     * as the test's code (README A-1), which the import resolves back to the same test.
     */
    @Transactional(readOnly = true)
    public byte[] export() {
        UUID hospitalId = priceList.requireReader();
        if (hospitalId == null) {
            throw new BusinessException("billing.priceList.export.hospitalRequired");
        }
        LocalDate today = priceList.today();
        List<PriceListItem> items = itemRepository.findByHospitalIdOrderByCodeAsc(hospitalId).stream()
            .filter(PriceListItem::isActive)
            .toList();
        Map<UUID, List<PriceListPrice>> versions = new HashMap<>();
        for (List<UUID> chunk : PriceListService.chunksOf(items.stream().map(PriceListItem::getId).toList())) {
            for (PriceListPrice price : priceRepository.findByItemIds(chunk)) {
                versions.computeIfAbsent(price.getItem().getId(), id -> new ArrayList<>()).add(price);
            }
        }
        Map<String, String> testCodes = testCodesOf(items);
        List<PriceListCsv.ExportRow> rows = new ArrayList<>();
        for (PriceListItem item : items) {
            List<PriceListPrice> live = versions.getOrDefault(item.getId(), List.of()).stream()
                .filter(v -> !v.isReplaced())
                .toList();
            Optional<PriceListPrice> shown = live.stream().filter(v -> v.coversDay(today)).findFirst()
                .or(() -> live.stream().filter(v -> v.getEffectiveFrom().isAfter(today))
                    .min(Comparator.comparing(PriceListPrice::getEffectiveFrom)));
            if (shown.isEmpty()) {
                continue;
            }
            String linkKey = item.getLinkType() == PriceListLinkType.LAB_TEST_DEFINITION
                ? testCodes.getOrDefault(item.getLinkKey(), item.getLinkKey())
                : item.getLinkKey();
            rows.add(new PriceListCsv.ExportRow(item.getCode(), item.getLabel(), item.getCategory().name(),
                shown.get().getUnitPrice(), shown.get().getEffectiveFrom(),
                item.getLinkType() == null ? null : item.getLinkType().name(), linkKey));
        }
        return PriceListCsv.write(rows);
    }

    /** Export file name: the hospital's business day, so two exports of one day overwrite each other. */
    public String exportFileName() {
        return "grille-tarifaire-" + priceList.today() + ".csv";
    }

    private Map<String, String> testCodesOf(List<PriceListItem> items) {
        List<UUID> ids = new ArrayList<>();
        for (PriceListItem item : items) {
            if (item.getLinkType() == PriceListLinkType.LAB_TEST_DEFINITION) {
                try {
                    ids.add(UUID.fromString(item.getLinkKey()));
                } catch (IllegalArgumentException notAnId) {
                    // Written by hand before V302's rule: exported as typed.
                }
            }
        }
        Map<String, String> codes = new HashMap<>();
        for (List<UUID> chunk : PriceListService.chunksOf(ids)) {
            for (LabTestDefinition test : labTestDefinitionRepository.findAllById(chunk)) {
                if (test.getTestCode() != null && !test.getTestCode().isBlank()) {
                    codes.put(test.getId().toString(), test.getTestCode());
                }
            }
        }
        return codes;
    }
}
