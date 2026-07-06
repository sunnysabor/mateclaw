package vip.mate.memory.event;

/**
 * Published when canonical memory files are written (MEMORY.md, structured/*.md).
 * Used by SoulSummarizerService for K-accumulate SOUL.md evolution.
 *
 * @param agentId the agent ID
 * @param target  which file was written (e.g. "MEMORY.md", "structured/user.md")
 * @param action  what happened ("remember", "consolidate", "update")
 * @param content full current content of the written canonical file. Providers
 *                rebuild derived projections from this value, so callers must
 *                not pass only a section body or delta.
 * @author MateClaw Team
 */
public record MemoryWriteEvent(
        Long agentId,
        String target,
        String action,
        String content,
        String ownerKey,
        String scope
) {
    public MemoryWriteEvent(Long agentId, String target, String action, String content) {
        this(agentId, target, action, content, null,
                vip.mate.memory.identity.MemoryScope.TEAM);
    }
}
