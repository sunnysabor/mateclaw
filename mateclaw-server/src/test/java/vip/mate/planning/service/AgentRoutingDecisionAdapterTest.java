package vip.mate.planning.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.mockito.ArgumentCaptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.decision.api.*;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.DecisionRecordStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRoutingDecisionAdapterTest {
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"routing-test"),AgentEntity.class);
    }
    AgentMapper agents = mock(AgentMapper.class);
    DecisionProperties properties = new DecisionProperties();
    AtomicInteger calls = new AtomicInteger();
    DecisionService core;
    AgentRoutingDecisionAdapter adapter;
    String proposed = "AGENT:3";
    @BeforeEach void setup() {
        properties.setMode(DecisionMode.ACTIVE);
        core = new DecisionService(properties, List.of(new RuleDecisionProvider() {
            @Override public DecisionResult decide(DecisionRequest request) {
                calls.incrementAndGet();
                return DecisionResult.proposed(new DecisionValue.Choice(proposed), 1, "test");
            }
        }), mock(DecisionRecordStore.class), new SimpleMeterRegistry());
        adapter = new AgentRoutingDecisionAdapter(core, properties, agents);
        when(agents.selectList(any())).thenReturn(List.of(agent(2L, "Research"), agent(3L, "Writing")));
    }
    @AfterEach void close() { core.close(); }
    static AgentEntity agent(long id, String name) {
        var agent = new AgentEntity(); agent.setId(id); agent.setName(name);
        agent.setWorkspaceId(10L); agent.setEnabled(true); return agent;
    }
    @Test void selectsPeerAndPreservesStepCardinality() {
        var selected = adapter.select(10L, "1", "conversation", "summarize", 2, Arrays.asList(2L, null));
        assertEquals(List.of(3L, 3L), adapter.assignments(selected));
        assertEquals(2, selected.steps().size());
    }
    @Test void explicitNameGuardsAllBaselineStepsEvenWhenPlannerMissesOrMismatches() {
        var selected = adapter.select(10L, "1", "conversation", "Ask Research to handle it", 2, Arrays.asList(3L, null));
        assertEquals(Arrays.asList(3L, null), adapter.assignments(selected));
        assertEquals(0, calls.get());
    }
    @Test void ineligibleCandidatesCannotBeAdoptedAndStaleBaselineFallsBackLocal() {
        var disabled = agent(3, "Writing"); disabled.setEnabled(false);
        var other = agent(4, "Other"); other.setWorkspaceId(11L);
        when(agents.selectList(any())).thenReturn(List.of(agent(1,"Parent"), disabled, other, agent(2,"Research")));
        var selection = adapter.select(10L,"1","conversation","summarize",1,List.of(2L));
        assertEquals(List.of(2L), adapter.assignments(selection));
        when(agents.selectList(any())).thenReturn(List.of());
        assertNull(adapter.assignments(selection));
    }
    @Test void staleProposedPeerFallsBackToStillEligibleBaseline() {
        var selected = adapter.select(10L,"1","conversation","summarize",1,List.of(2L));
        when(agents.selectList(any())).thenReturn(List.of(agent(2,"Research")));
        assertEquals(List.of(2L), adapter.assignments(selected));
    }
    @Test void oversizedCandidateSetGuardsBaselineWithoutTruncating() {
        when(agents.selectList(any())).thenReturn(LongStream.range(2,66).mapToObj(id -> agent(id,"Peer"+id)).toList());
        var selected = adapter.select(10L,"1","conversation","summarize",2,Arrays.asList(65L,null));
        assertEquals(Arrays.asList(65L,null), adapter.assignments(selected));
        assertEquals(0,calls.get());
    }
    @Test void shadowRetainsExcludedLegacyBaselineInTicketAndAssignments() {
        properties.setMode(DecisionMode.SHADOW);
        var selected = adapter.select(10L,"1","conversation","summarize",2,Arrays.asList(99L,null));
        assertEquals(new DecisionValue.Choice("AGENT:99"), selected.steps().getFirst().ticket().effectiveValue());
        assertEquals(Arrays.asList(99L,null),adapter.assignments(selected));
    }
    @Test void shadowPreparationFailureReturnsLegacyPathAndActiveFailsClosed() {
        when(agents.selectList(any())).thenThrow(new IllegalStateException("unavailable"));
        properties.setMode(DecisionMode.SHADOW);
        assertNull(adapter.select(10L,"1","conversation","summarize",1,List.of(2L)));
        properties.setMode(DecisionMode.ACTIVE);
        assertThrows(DecisionRecordingException.class, () -> adapter.select(10L,"1","conversation","summarize",1,List.of(2L)));
    }
    @Test void disabledOrParentUserNameStillGuardsLocalButPlannerNameAloneDoesNot() {
        var disabled = agent(4,"Unavailable"); disabled.setEnabled(false);
        when(agents.selectList(any())).thenReturn(List.of(disabled,agent(1,"Parent"),agent(3,"Writing")));
        assertNull(adapter.assignments(adapter.select(10L,"1","conversation","Ask Unavailable",1,null)));
        assertNull(adapter.assignments(adapter.select(10L,"1","conversation","Ask Parent",1,null)));
        assertEquals(0,calls.get());
        assertEquals(List.of(3L),adapter.assignments(adapter.select(10L,"1","conversation","summarize",1,List.of(3L))));
        assertEquals(1,calls.get());
    }

    @Test void revalidationIsScopedBeforeLockingAndNullWorkspaceDoesNotLock() {
        var selected=adapter.select(10L,"1","conversation","summarize",1,List.of(99L));
        clearInvocations(agents);assertNull(adapter.assignments(selected));
        ArgumentCaptor<LambdaQueryWrapper<AgentEntity>> query=ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(agents).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("workspace_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(10L));
        var unscoped=adapter.select(null,"1","conversation","summarize",1,List.of(2L));
        clearInvocations(agents);assertNull(adapter.assignments(unscoped));verifyNoInteractions(agents);
    }
    @Test void localAssignmentsRetainNullListAndOffDoesNotReadCandidates() {
        proposed="LOCAL";
        assertNull(adapter.assignments(adapter.select(10L,"1","conversation","summarize",2,null)));
        properties.setMode(DecisionMode.OFF); clearInvocations(agents);
        assertNull(adapter.select(10L,"1","conversation","summarize",2,null));
        verifyNoInteractions(agents);
    }
}
