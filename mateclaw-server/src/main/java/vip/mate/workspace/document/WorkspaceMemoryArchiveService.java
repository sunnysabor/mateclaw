package vip.mate.workspace.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Snapshot / restore for agent workspace memory files.
 * <p>
 * The agent's visible memory surface lives in a small, fixed set of Markdown
 * files — the top-level memory files ({@code AGENTS.md}, {@code MEMORY.md},
 * {@code PROFILE.md}, {@code SOUL.md}, {@code KNOWLEDGE.md}, {@code DREAMS.md}),
 * typed structured memory under {@code structured/*.md}, the daily ledger under
 * {@code memory/YYYY-MM-DD.md}, and dream archives under
 * {@code memory/dreams/YYYY-MM.md}. With per-owner isolation enabled,
 * export/import operates on the same visible surface as runtime recall:
 * shared rows plus the current owner's PERSONAL rows, with PERSONAL taking
 * precedence for the same filename.
 * <p>
 * Three operations:
 * <ul>
 *   <li>{@link #export} — Build a ZIP of the agent's whitelisted memory
 *       files plus a {@code manifest.json}. {@code enabled} / {@code sortOrder}
 *       are deliberately NOT serialised — those are UI preferences and an
 *       import must NOT clobber the local toggle state.</li>
 *   <li>{@link #previewImport} — Parse the ZIP without writing anything.
 *       Classifies every entry as create / update (with old/new size + hash)
 *       / skip (with a reason). Required so the UI can show a diff before the
 *       user confirms.</li>
 *   <li>{@link #apply} — Same classification, but actually persist; runs in
 *       a single Spring transaction so an OutOfMemory on entry N-1 doesn't
 *       leave the agent's memory half-rewritten.</li>
 * </ul>
 * <p>
 * Defences:
 * <ul>
 *   <li>White-list match per entry name (regex + literal set); no path-traversal,
 *       no absolute paths, no arbitrary files. Anything outside lands in the
 *       skip list, never in the file table.</li>
 *   <li>ZIP-bomb caps: ≤ {@link #MAX_ENTRIES} entries, ≤ {@link #MAX_ENTRY_BYTES}
 *       per entry (post-decompression), ≤ {@link #MAX_TOTAL_BYTES} total.</li>
 *   <li>Ownership gate ({@link #assertOwnership}) — the caller-supplied
 *       {@code workspaceId} must match the target agent's row. Controller-level
 *       {@code @RequireWorkspaceRole} guards the HTTP route; this is the
 *       service-layer second line of defence so a unit test or alternative
 *       caller can't bypass it.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceMemoryArchiveService {

    /** Regex match for the per-day ledger filenames under {@code memory/}.
     *  Tight enough to reject path-traversal ({@code memory/../etc}), Windows
     *  separators ({@code memory\\2026-05-15.md}), and anything that isn't a
     *  literal {@code memory/YYYY-MM-DD.md}. */
    private static final Pattern DAILY_FILENAME =
            Pattern.compile("^memory/\\d{4}-\\d{2}-\\d{2}\\.md$");

    /** Typed structured memory files. Only one path segment is allowed after
     *  {@code structured/}; this keeps the import surface narrow while still
     *  preserving all first-class structured memory buckets. */
    private static final Pattern STRUCTURED_FILENAME =
            Pattern.compile("^structured/[A-Za-z0-9_-]+\\.md$");

    /** Monthly dream diary archives produced by {@code MemoryArchiveService}. */
    private static final Pattern DREAM_ARCHIVE_FILENAME =
            Pattern.compile("^memory/dreams/\\d{4}-\\d{2}\\.md$");

    /** Top-level whitelist. Anything outside lands in the skip list with reason
     *  {@code "not in whitelist"} so the user can see why their {@code secrets.txt}
     *  was ignored. */
    private static final Set<String> TOP_LEVEL_WHITELIST = Set.of(
            "AGENTS.md", "MEMORY.md", "PROFILE.md", "SOUL.md", "KNOWLEDGE.md", "DREAMS.md");

    /** Per-entry decompressed-size cap. 1 MB comfortably covers a heavy
     *  Markdown memory file but rejects pathological "1 GB of zeroes"
     *  payloads that decompress quickly. */
    public static final long MAX_ENTRY_BYTES = 1L * 1024 * 1024;

    /** Total decompressed size across the archive. 16 MB ≈ 16 fat memory files.
     *  Doubles as the upper bound on the COMPRESSED upload too — a legitimate
     *  memory bundle compresses to single-digit MB, so anything over 16 MB
     *  compressed is either malicious or accidental. The controller uses this
     *  to short-circuit a multipart upload before the bytes ever land in
     *  heap (avoids loading a 100 MB compressed file just to reject it). */
    public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    /** Hard limit on entries in one archive. The full whitelisted memory set
     *  is a few top-level / structured files + at most ~365 daily ledger files
     *  per year, so 500 is a comfortable ceiling. */
    public static final int MAX_ENTRIES = 500;

    /** Manifest file at the root of the export bundle. Optional on import —
     *  the import path is lenient so a user editing one file in a tar /
     *  re-zipping by hand doesn't have to know about it. */
    static final String MANIFEST_NAME = "manifest.json";

    /** Bundle version. Bumped when the on-disk schema changes
     *  incompatibly (none planned for v1). */
    static final int BUNDLE_VERSION = 1;

    private final WorkspaceFileService workspaceFileService;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    // ==================== Export ====================

    public byte[] export(Long agentId, Long workspaceId) {
        return export(agentId, workspaceId, null);
    }

    public byte[] export(Long agentId, Long workspaceId, String ownerKey) {
        AgentEntity agent = assertOwnership(agentId, workspaceId);

        String effectiveOwner = isPersonal(ownerKey) ? ownerKey : null;
        List<WorkspaceFileEntity> all = effectiveOwner != null
                ? dedupeByFilenamePreferPersonal(workspaceFileService.listVisibleFiles(agentId, effectiveOwner))
                : workspaceFileService.listFiles(agentId);
        // Listing strips content for transport — re-fetch each allowed visible
        // file by name so we can write its body into the archive.
        List<WorkspaceFileEntity> exportable = new ArrayList<>();
        for (WorkspaceFileEntity meta : all) {
            String name = meta.getFilename();
            if (name != null && isAllowedFilename(name)) {
                WorkspaceFileEntity full = effectiveOwner != null
                        ? workspaceFileService.getVisibleFile(agentId, name, effectiveOwner)
                        : workspaceFileService.getFile(agentId, name);
                if (full != null && full.getContent() != null) {
                    exportable.add(full);
                }
            }
        }
        exportable.sort(Comparator.comparing(WorkspaceFileEntity::getFilename));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            // Manifest first — lets a human extracting the bundle see the
            // provenance without opening every .md file.
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("version", BUNDLE_VERSION);
            manifest.put("exportedAt", Instant.now().toString());
            manifest.put("agentId", agentId);
            manifest.put("agentName", agent.getName());
            writeZipEntry(zip, MANIFEST_NAME,
                    objectMapper.writeValueAsBytes(manifest));

            for (WorkspaceFileEntity file : exportable) {
                byte[] body = file.getContent().getBytes(StandardCharsets.UTF_8);
                writeZipEntry(zip, file.getFilename(), body);
            }
        } catch (IOException e) {
            throw new MateClawException(500, "Failed to build memory archive: " + e.getMessage());
        }
        return baos.toByteArray();
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, byte[] body) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(body);
        zip.closeEntry();
    }

    // ==================== Preview ====================

    public ImportPreview previewImport(Long agentId, Long workspaceId, byte[] zipBytes) {
        return previewImport(agentId, workspaceId, zipBytes, null);
    }

    public ImportPreview previewImport(Long agentId, Long workspaceId, byte[] zipBytes, String ownerKey) {
        assertOwnership(agentId, workspaceId);
        Map<String, byte[]> entries = readAndValidateZip(zipBytes);
        return classify(agentId, entries, /* applyWrites */ false, null, ownerKey);
    }

    // ==================== Apply ====================

    @Transactional
    public ImportResult apply(Long agentId, Long workspaceId, byte[] zipBytes) {
        return apply(agentId, workspaceId, zipBytes, null);
    }

    public ImportResult apply(Long agentId, Long workspaceId, byte[] zipBytes, String ownerKey) {
        assertOwnership(agentId, workspaceId);
        Map<String, byte[]> entries = readAndValidateZip(zipBytes);
        int[] counter = new int[]{0};
        ImportPreview preview = classify(agentId, entries, /* applyWrites */ true, counter, ownerKey);
        return new ImportResult(counter[0], preview.willSkip.size());
    }

    // ==================== Internals ====================

    private AgentEntity assertOwnership(Long agentId, Long workspaceId) {
        if (agentId == null) {
            throw new MateClawException(400, "agentId is required");
        }
        if (workspaceId == null) {
            throw new MateClawException(400, "workspaceId is required");
        }
        AgentEntity agent = agentService.getAgent(agentId);
        if (agent == null) {
            throw new MateClawException(404, "Agent not found: " + agentId);
        }
        if (!Objects.equals(agent.getWorkspaceId(), workspaceId)) {
            // Wording deliberately generic — does not leak the agent's actual
            // workspace assignment to a caller who has no business knowing it.
            throw new MateClawException(403,
                    "Agent " + agentId + " does not belong to workspace " + workspaceId);
        }
        return agent;
    }

    private static boolean isAllowedFilename(String name) {
        if (TOP_LEVEL_WHITELIST.contains(name)) {
            return true;
        }
        if (STRUCTURED_FILENAME.matcher(name).matches()) {
            return true;
        }
        if (DAILY_FILENAME.matcher(name).matches()) {
            // The regex only constrains digit shape, so structurally-valid but
            // non-existent dates (memory/2026-13-99.md, memory/2026-02-30.md)
            // would slip through. Parse the date to reject calendar dates that
            // cannot occur, keeping the daily ledger namespace clean.
            String date = name.substring("memory/".length(), name.length() - ".md".length());
            try {
                LocalDate.parse(date);
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        if (DREAM_ARCHIVE_FILENAME.matcher(name).matches()) {
            String month = name.substring("memory/dreams/".length(), name.length() - ".md".length());
            try {
                YearMonth.parse(month);
                return true;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Decompress the ZIP under the bomb caps. Returns a name → body map for
     * every entry whose decompressed body fits the per-entry cap; oversized
     * entries throw {@link MateClawException} 400 immediately rather than
     * silently dropping them. The total-bytes and entry-count caps short
     * the whole stream so a malicious archive can never burn more than
     * {@link #MAX_TOTAL_BYTES} of heap.
     */
    private static Map<String, byte[]> readAndValidateZip(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new MateClawException(400, "Empty archive");
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        long totalBytes = 0;
        int entryCount = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new MateClawException(400,
                            "Archive has too many entries (> " + MAX_ENTRIES + ")");
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String name = entry.getName();
                // Bounded read — read at most MAX_ENTRY_BYTES + 1 so we can
                // tell "fits the cap" from "exceeded the cap" deterministically
                // without trusting entry.getSize() (which a malicious crafter
                // can set to anything).
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                long entryBytes = 0;
                int n;
                while ((n = zip.read(chunk)) > 0) {
                    entryBytes += n;
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new MateClawException(400,
                                "Archive entry " + name + " exceeds size limit (> "
                                        + MAX_ENTRY_BYTES + " bytes)");
                    }
                    totalBytes += n;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new MateClawException(400,
                                "Archive total size exceeds limit (> "
                                        + MAX_TOTAL_BYTES + " bytes)");
                    }
                    buf.write(chunk, 0, n);
                }
                zip.closeEntry();
                out.put(name, buf.toByteArray());
            }
        } catch (IOException e) {
            throw new MateClawException(400, "Failed to read archive: " + e.getMessage());
        }
        return out;
    }

    /**
     * Classify each archive entry into create / update / skip buckets. When
     * {@code applyWrites} is true, the create + update entries are persisted
     * via {@link WorkspaceFileService#saveFile} and {@code counter[0]} is
     * incremented per persisted row.
     */
    private ImportPreview classify(Long agentId, Map<String, byte[]> entries,
                                    boolean applyWrites, int[] counter, String ownerKey) {
        String effectiveOwner = isPersonal(ownerKey) ? ownerKey : null;
        List<String> willCreate = new ArrayList<>();
        List<FileDiff> willUpdate = new ArrayList<>();
        List<SkipEntry> willSkip = new ArrayList<>();

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            byte[] body = e.getValue();

            if (MANIFEST_NAME.equals(name)) {
                // Manifest is informational; never written as a workspace file.
                willSkip.add(new SkipEntry(name, "manifest entry"));
                continue;
            }
            if (!isAllowedFilename(name)) {
                willSkip.add(new SkipEntry(name, "not in whitelist"));
                continue;
            }

            String newContent = new String(body, StandardCharsets.UTF_8);
            String newHash = sha256Hex(body);

            WorkspaceFileEntity existing = effectiveOwner != null
                    ? workspaceFileService.getVisibleFile(agentId, name, effectiveOwner)
                    : workspaceFileService.getFile(agentId, name);
            if (existing == null) {
                willCreate.add(name);
                if (applyWrites) {
                    saveVisible(agentId, name, newContent, effectiveOwner);
                    counter[0]++;
                }
                continue;
            }

            String existingContent = existing.getContent() != null ? existing.getContent() : "";
            byte[] existingBytes = existingContent.getBytes(StandardCharsets.UTF_8);
            String oldHash = sha256Hex(existingBytes);
            if (oldHash.equals(newHash)) {
                willSkip.add(new SkipEntry(name, "unchanged"));
                continue;
            }
            willUpdate.add(new FileDiff(name, existingBytes.length, body.length, oldHash, newHash));
            if (applyWrites) {
                saveVisible(agentId, name, newContent, effectiveOwner);
                counter[0]++;
            }
        }
        return new ImportPreview(willCreate, willUpdate, willSkip);
    }

    private void saveVisible(Long agentId, String name, String content, String ownerKey) {
        if (isPersonal(ownerKey)) {
            workspaceFileService.saveMemoryFile(agentId, name, content, ownerKey);
        } else {
            workspaceFileService.saveFile(agentId, name, content);
        }
    }

    private boolean isPersonal(String ownerKey) {
        return ownerKey != null && !ownerKey.isBlank()
                && !vip.mate.memory.identity.MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey);
    }

    private List<WorkspaceFileEntity> dedupeByFilenamePreferPersonal(List<WorkspaceFileEntity> files) {
        Map<String, WorkspaceFileEntity> byName = new LinkedHashMap<>();
        for (WorkspaceFileEntity file : files) {
            if (file == null || file.getFilename() == null) continue;
            WorkspaceFileEntity existing = byName.get(file.getFilename());
            if (existing == null || vip.mate.memory.identity.MemoryScope.PERSONAL.equals(file.getScope())) {
                byName.put(file.getFilename(), file);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JDK; this is fatal not a 500.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ==================== DTOs ====================

    public record ImportPreview(List<String> willCreate,
                                 List<FileDiff> willUpdate,
                                 List<SkipEntry> willSkip) {}

    public record FileDiff(String filename, long oldSize, long newSize,
                           String oldHash, String newHash) {}

    public record SkipEntry(String filename, String reason) {}

    public record ImportResult(int applied, int skipped) {}
}
