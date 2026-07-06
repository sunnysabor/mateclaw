package vip.mate.memory.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.identity.MemoryScope;

/**
 * Publishes canonical memory-file writes in one place.
 * <p>
 * Only files that feed derived memory projections are emitted:
 * {@code MEMORY.md} and {@code structured/*.md}. The event payload must be the
 * full current file content, not a patch or a single section, because providers
 * such as the fact projection rebuild the changed file from this text and
 * soft-delete facts that disappeared from it.
 */
@Component
@RequiredArgsConstructor
public class MemoryWritePublisher {

    private final ApplicationEventPublisher events;

    public void publishIfCanonical(Long agentId, String target, String action, String fullContent) {
        publishIfCanonical(agentId, target, action, fullContent, null);
    }

    public void publishIfCanonical(Long agentId, String target, String action,
                                   String fullContent, String ownerKey) {
        if (agentId == null || !isCanonicalTarget(target)) {
            return;
        }
        boolean personal = isPersonalOwner(ownerKey);
        events.publishEvent(new MemoryWriteEvent(
                agentId,
                target,
                action != null ? action : "update",
                fullContent != null ? fullContent : "",
                personal ? ownerKey : null,
                personal ? MemoryScope.PERSONAL : MemoryScope.TEAM));
    }

    public boolean isCanonicalTarget(String target) {
        return "MEMORY.md".equals(target)
                || (target != null && target.startsWith("structured/") && target.endsWith(".md"));
    }

    private boolean isPersonalOwner(String ownerKey) {
        return ownerKey != null && !ownerKey.isBlank()
                && !MemoryOwnerResolver.SYSTEM_OWNER.equals(ownerKey);
    }
}
