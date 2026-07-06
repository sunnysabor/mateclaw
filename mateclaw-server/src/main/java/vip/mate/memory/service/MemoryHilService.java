package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.util.regex.Pattern;

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
        SectionBounds section = findSection(fileContent, key);

        String updated;
        if (section == null) {
            // Section not found — append as a new section.
            String newSection = sectionHeader + "\n" + cleanContent + "\n" + metadata;
            updated = fileContent.isBlank() ? newSection : fileContent.trim() + "\n\n" + newSection;
        } else {
            // Replace the existing section body, keeping the heading in place.
            String replacement = cleanContent + "\n" + metadata + "\n";
            updated = fileContent.substring(0, section.contentStart()) + replacement
                    + fileContent.substring(section.sectionEnd());
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
        return findSection(file.getContent(), key) != null;
    }

    /** Owner-scoped section lookup matching {@link #editMemoryEntry(Long, String, String, String, String)}. */
    public boolean sectionExists(Long agentId, String filename, String key, String ownerKey) {
        WorkspaceFileEntity file = workspaceFileService.getVisibleFile(agentId, filename, ownerKey);
        if (file == null || file.getContent() == null) return false;
        return findSection(file.getContent(), key) != null;
    }

    /**
     * Locate an exact level-2 heading. The old substring search let key
     * {@code "Fact"} match {@code "## Facts"} and could overwrite the wrong
     * user-edited section. Matching whole heading lines keeps HiL edits scoped
     * to the section the user actually opened.
     */
    private SectionBounds findSection(String content, String key) {
        if (content == null || content.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        Pattern target = Pattern.compile("(?m)^##\\s+" + Pattern.quote(key.trim()) + "\\s*$");
        java.util.regex.Matcher m = target.matcher(content);
        if (!m.find()) {
            return null;
        }
        int contentStart = m.end();
        while (contentStart < content.length()) {
            char ch = content.charAt(contentStart);
            if (ch == '\n' || ch == '\r') {
                contentStart++;
            } else {
                break;
            }
        }

        java.util.regex.Matcher next = SECTION_HEADER.matcher(content);
        int sectionEnd = content.length();
        if (next.find(contentStart)) {
            sectionEnd = next.start();
        }
        return new SectionBounds(contentStart, sectionEnd);
    }

    private record SectionBounds(int contentStart, int sectionEnd) {}
}
