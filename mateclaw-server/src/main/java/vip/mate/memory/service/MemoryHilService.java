package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import vip.mate.exception.MateClawException;

/**
 * Human-in-the-Loop service for memory editing.
 * <p>
 * When a user edits a memory entry, this service writes it back to the target
 * memory file (MEMORY.md, PROFILE.md, SOUL.md, ...) with a hidden metadata
 * marker ({@code <!-- user-edited: YYYY-MM-DD -->}) so that future Dream runs
 * do not overwrite user modifications.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryHilService {

    /** Matches a whole-line user-edited marker so repeated edits do not accumulate markers. */
    private static final Pattern USER_EDITED_MARKER =
            Pattern.compile("(?m)^[ \\t]*<!-- user-edited:.*-->[ \\t]*\\r?\\n?");
    /** Matches any level-2 Markdown section heading. */
    private static final Pattern SECTION_HEADER = Pattern.compile("(?m)^##\\s+.+\\s*$");

    private final WorkspaceFileService workspaceFileService;

    /**
     * Edit a section identified by key (section heading) inside {@code filename}.
     * Appends user-edited metadata so Dream prompts respect user changes.
     *
     * @param agentId    the agent whose workspace file is edited
     * @param filename   the target memory file (e.g. MEMORY.md / PROFILE.md / SOUL.md)
     * @param key        the section heading (text after {@code ## })
     * @param newContent the new section body
     */
    public void editMemoryEntry(Long agentId, String filename, String key, String newContent) {
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
        String updated = rewriteSection(file, key, newContent);
        workspaceFileService.saveFile(agentId, filename, updated);
        log.info("[HiL] User edited {} section '{}' for agent={}", filename, key, agentId);
    }

    /**
     * Owner-scoped variant used by the Memory UI: when lifecycle-mediated
     * personal memory is enabled, users must edit the same PERSONAL file bucket
     * that the runtime injects for their turns. A null/system owner preserves
     * the legacy shared-file behavior.
     */
    public void editMemoryEntry(Long agentId, String filename, String key, String newContent, String ownerKey) {
        WorkspaceFileEntity file = workspaceFileService.getVisibleFile(agentId, filename, ownerKey);
        String updated = rewriteSection(file, key, newContent);
        workspaceFileService.saveVisibleFile(agentId, filename, updated, ownerKey);
        log.info("[HiL] User edited {} section '{}' for agent={}", filename, key, agentId);
    }

    private String rewriteSection(WorkspaceFileEntity file, String key, String newContent) {
        String fileContent = (file != null && file.getContent() != null) ? file.getContent() : "";
        // Strip any pre-existing user-edited markers from the incoming body so a
        // section edited multiple times does not pick up a stack of markers.
        String cleanContent = USER_EDITED_MARKER.matcher(newContent).replaceAll("").trim();
        String metadata = "<!-- user-edited: " + LocalDate.now() + " -->";
        String sectionHeader = "## " + key;
        String newline = fileContent.contains("\r\n") ? "\r\n" : "\n";
        List<Section> matches = sections(fileContent).stream().filter(section -> section.key().equals(key)).toList();
        if (matches.size() > 1) {
            throw new MateClawException("err.memory.section_ambiguous", 409,
                    "Memory section is ambiguous. Rename duplicate headings before editing.");
        }
        String updated;
        if (matches.isEmpty()) {
            String newSection = sectionHeader + newline + cleanContent + newline + metadata;
            updated = fileContent.isBlank() ? newSection : fileContent.trim() + newline + newline + newSection;
        } else {
            Section section = matches.getFirst();
            String prefix = fileContent.substring(0, section.bodyStart());
            if (!prefix.endsWith("\n") && !prefix.endsWith("\r")) prefix += newline;
            updated = prefix + cleanContent + newline + metadata + newline
                    + fileContent.substring(section.end());
        }
        return updated;
    }

    /**
     * Check if a section heading exists in {@code filename}.
     * Used by DreamController to validate the edit key before allowing a write.
     */
    public boolean sectionExists(Long agentId, String filename, String key) {
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
        if (file == null || file.getContent() == null) return false;
        return sections(file.getContent()).stream().anyMatch(section -> section.key().equals(key));
    }
    private record Section(String key, int bodyStart, int end) {}

    /** A heading is a complete line, never an inline substring or another key's prefix. */
    private static List<Section> sections(String content) {
        var matcher = Pattern.compile("(?m)^## ([^\r\n]*)(?:\r\n|\n|\r|$)").matcher(content);
        List<Section> result = new ArrayList<>();
        while (matcher.find()) {
            if (!result.isEmpty()) {
                Section previous = result.removeLast();
                result.add(new Section(previous.key(), previous.bodyStart(), matcher.start()));
            }
            result.add(new Section(matcher.group(1).trim(), matcher.end(), content.length()));
        }
        return result;
    }

}
