package vip.mate.planning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;

import java.util.*;

/** Optional per-step routing; planner assignments remain the compatibility baseline. */
@Service
public class AgentRoutingDecisionAdapter {
    private final DecisionService decisions;
    private final DecisionProperties properties;
    private final AgentMapper agents;

    public AgentRoutingDecisionAdapter(DecisionService decisions, DecisionProperties properties, AgentMapper agents) {
        this.decisions = decisions; this.properties = properties; this.agents = agents;
    }

    public record Step(Long baseline, DecisionTicket ticket) {}
    public record Selection(Long workspaceId, String parentId, List<Step> steps) {
        public Selection { steps = List.copyOf(steps); }
        public boolean active() { return steps.stream().anyMatch(step -> step.ticket().mode() == DecisionMode.ACTIVE); }
    }
    private record Candidate(Long id, String name) {}

    public boolean enabled() { return properties.modeFor(DecisionType.AGENT_ROUTING) != DecisionMode.OFF; }

    /** Provider work happens here, before the plan persistence transaction. */
    public Selection select(Long workspaceId, String parentId, String conversationId, String originalGoal,
                            int stepCount, List<Long> baseline) {
        if (!enabled()) return null;
        try {
            List<AgentEntity> catalog = workspaceId == null ? List.of() : agents.selectList(
                    Wrappers.<AgentEntity>lambdaQuery().eq(AgentEntity::getWorkspaceId, workspaceId)
                            .eq(AgentEntity::getDeleted, 0).orderByAsc(AgentEntity::getId));
            List<Candidate> candidates = eligible(catalog, workspaceId, parentId);
            Set<Long> eligible = new HashSet<>();
            candidates.forEach(candidate -> eligible.add(candidate.id()));
            String goal = originalGoal == null ? "" : originalGoal.toLowerCase(Locale.ROOT);
            boolean explicit = catalog.stream().filter(agent -> agent != null && Objects.equals(workspaceId, agent.getWorkspaceId()))
                    .anyMatch(agent -> agent.getName() != null && !agent.getName().isBlank()
                            && goal.contains(agent.getName().trim().toLowerCase(Locale.ROOT)));
            boolean oversized = candidates.size() > 63;
            List<Step> selections = new ArrayList<>();
            for (int index = 0; index < stepCount; index++) {
                Long old = baseline != null && index < baseline.size() ? baseline.get(index) : null;
                boolean stale = old != null && !eligible.contains(old);
                List<DecisionQuestion.Option> options = new ArrayList<>();
                options.add(option(null));
                // An excluded old ID is representable only in a guarded request: SHADOW must
                // describe the unchanged legacy assignment, while ACTIVE must use LOCAL.
                if (oversized || stale) {
                    if (old != null) options.add(option(old));
                } else {
                    candidates.forEach(candidate -> options.add(option(candidate.id())));
                }
                DecisionValue oldValue = value(old);
                DecisionValue guard = stale ? value(null) : explicit || oversized ? oldValue : null;
                var facts = new DecisionFacts(Map.of("EXPLICIT_AGENT", explicit, "CANDIDATE_LIMIT", oversized,
                        "BASELINE_INELIGIBLE", stale), Map.of("STEP_INDEX", (double) index));
                var scope = new DecisionScope(workspaceId, numeric(parentId), null, identifier(conversationId), null);
                DecisionTicket ticket = decisions.decide(new DecisionRequest(DecisionType.AGENT_ROUTING, scope,
                        "PLAN_STEP", new DecisionQuestion.Choice("agent-routing-v1", "Assign a planner step", options),
                        facts, oldValue, guard, null));
                selections.add(new Step(old, ticket));
            }
            return new Selection(workspaceId, parentId, selections);
        } catch (RuntimeException failure) {
            if (properties.modeFor(DecisionType.AGENT_ROUTING) == DecisionMode.ACTIVE) throw new DecisionRecordingException();
            return null;
        }
    }

    /** ACTIVE uses locking reads so eligibility is current even under repeatable-read isolation. */
    public List<Long> assignments(Selection selection) {
        Set<Long> eligible = new HashSet<>();
        if (selection.active()) {
            Set<Long> ids = new TreeSet<>();
            for (Step step : selection.steps()) {
                if (step.baseline() != null) ids.add(step.baseline());
                Long proposed = agentId(step.ticket().effectiveValue());
                if (proposed != null) ids.add(proposed);
            }
            if (selection.workspaceId() != null && !ids.isEmpty()) {
                List<AgentEntity> current = agents.selectList(Wrappers.<AgentEntity>lambdaQuery()
                        .eq(AgentEntity::getWorkspaceId, selection.workspaceId())
                        .in(AgentEntity::getId, ids).orderByAsc(AgentEntity::getId).last("FOR UPDATE"));
                eligible(current, selection.workspaceId(), selection.parentId()).forEach(candidate -> eligible.add(candidate.id()));
            }
        }
        List<Long> result = new ArrayList<>();
        for (Step step : selection.steps()) {
            Long assigned = step.baseline();
            if (step.ticket().mode() == DecisionMode.ACTIVE) {
                Long proposed = agentId(step.ticket().effectiveValue());
                assigned = proposed == null || eligible.contains(proposed) ? proposed
                        : step.baseline() != null && eligible.contains(step.baseline()) ? step.baseline() : null;
            }
            result.add(assigned);
        }
        return result.stream().allMatch(Objects::isNull) ? null : Collections.unmodifiableList(result);
    }

    public void record(Selection selection, List<Long> actual) {
        for (int index = 0; index < selection.steps().size(); index++) {
            Step step = selection.steps().get(index);
            DecisionValue value = value(actual == null ? null : actual.get(index));
            DecisionOutcome outcome = step.ticket().mode() == DecisionMode.SHADOW ? DecisionOutcome.OBSERVED
                    : value.equals(step.ticket().effectiveValue()) ? DecisionOutcome.APPLIED : DecisionOutcome.NOT_APPLIED;
            decisions.recordOutcome(step.ticket(), outcome, value);
        }
    }

    private List<Candidate> eligible(List<AgentEntity> catalog, Long workspaceId, String parentId) {
        if (workspaceId == null) return List.of();
        var byId = new TreeMap<Long, Candidate>();
        for (AgentEntity agent : catalog) {
            if (agent != null && agent.getId() != null && agent.getId() > 0
                    && (agent.getDeleted() == null || agent.getDeleted() == 0)
                    && Boolean.TRUE.equals(agent.getEnabled()) && workspaceId.equals(agent.getWorkspaceId())
                    && !Objects.equals(agent.getId(), numeric(parentId))) {
                byId.putIfAbsent(agent.getId(), new Candidate(agent.getId(), agent.getName()));
            }
        }
        return List.copyOf(byId.values());
    }
    private static DecisionQuestion.Option option(Long id) {
        return new DecisionQuestion.Option(value(id).code(), id == null ? "Execute with parent" : "Delegate to eligible peer");
    }
    private static DecisionValue.Choice value(Long id) { return new DecisionValue.Choice(id == null ? "LOCAL" : "AGENT:" + id); }
    private static Long agentId(DecisionValue value) {
        String code = ((DecisionValue.Choice) value).code();
        return "LOCAL".equals(code) ? null : Long.valueOf(code.substring("AGENT:".length()));
    }
    private static Long numeric(String value) {
        try { return Long.valueOf(value); } catch (RuntimeException ignored) { return null; }
    }
    private static String identifier(String value) { return value == null || value.isBlank() || value.length() > 128 ? null : value; }
}
