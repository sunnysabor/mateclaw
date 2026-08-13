package vip.mate.team.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.team.model.TeamRunEntity;
import vip.mate.team.model.TeamRunStatus;
import vip.mate.team.model.TeamRunView;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.team.repository.TeamRunMapper;
import vip.mate.team.repository.TeamTaskMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamRunProjectorTest {

    private static final Long RUN_ID = 20L;

    private TeamRunMapper runMapper;
    private TeamTaskMapper taskMapper;
    private TeamRunProjector projector;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamTaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        runMapper = mock(TeamRunMapper.class);
        taskMapper = mock(TeamTaskMapper.class);
        projector = new TeamRunProjector(runMapper, taskMapper);
    }

    @Test
    void projectsStatusAndReturnsComputedProgress() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(TeamRunStatus.AWAITING_REVIEW, null));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(TeamTaskStatus.COMPLETED), task(TeamTaskStatus.PENDING)));
        when(runMapper.update(isNull(), any())).thenReturn(1);

        TeamRunView view = projector.project(RUN_ID);

        assertEquals(TeamRunStatus.RUNNING, view.status());
        assertEquals(new TeamRunView.Progress(2, 1, 0, 0, 50), view.progress());
        ArgumentCaptor<LambdaUpdateWrapper<TeamRunEntity>> captor = updateCaptor();
        verify(runMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().toUpperCase().contains("METADATA IS NULL"));
    }

    @Test
    void projectsTaskDependenciesAndMetadataWithoutReencodingIds() {
        TeamTaskEntity task = task(TeamTaskStatus.BLOCKED);
        task.setBlockedBy("[\"9007199254740993\"]");
        task.setMetadata("{\"deliverables\":[],\"planId\":\"9007199254740995\"}");
        when(runMapper.selectById(RUN_ID)).thenReturn(run(TeamRunStatus.PLANNING, null));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        TeamRunView.Task projected = projector.project(RUN_ID).tasks().getFirst();

        assertEquals("[\"9007199254740993\"]", projected.blockedBy());
        assertEquals("{\"deliverables\":[],\"planId\":\"9007199254740995\"}", projected.metadata());
    }

    @Test
    void terminalRunCannotBeMovedByLateTaskEvents() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(TeamRunStatus.CANCELLED, "{\"traceId\":\"a\"}"));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(TeamTaskStatus.PENDING)));

        TeamRunView view = projector.project(RUN_ID);

        assertEquals(TeamRunStatus.CANCELLED, view.status());
        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void concurrentCancellationWinsProjectionCompareAndSet() {
        TeamRunEntity running = run(TeamRunStatus.RUNNING, null);
        TeamRunEntity cancelled = run(TeamRunStatus.CANCELLED, null);
        when(runMapper.selectById(RUN_ID)).thenReturn(running, cancelled);
        when(taskMapper.selectList(any())).thenReturn(List.of(task(TeamTaskStatus.COMPLETED)));
        when(runMapper.update(isNull(), any())).thenReturn(0);

        TeamRunView view = projector.project(RUN_ID);

        assertEquals(TeamRunStatus.CANCELLED, view.status());
    }

    @Test
    void failedCompareAndSetReloadsRunAndTasksBeforeRecomputing() {
        TeamRunEntity firstRun = run(TeamRunStatus.RUNNING, "{\"revision\":1}");
        TeamRunEntity secondRun = run(TeamRunStatus.RUNNING, "{\"revision\":2}");
        TeamTaskEntity completed = task(TeamTaskStatus.COMPLETED);
        TeamTaskEntity pending = task(TeamTaskStatus.PENDING);
        when(runMapper.selectById(RUN_ID)).thenReturn(firstRun, secondRun);
        when(taskMapper.selectList(any())).thenReturn(List.of(completed), List.of(pending));
        when(runMapper.update(isNull(), any())).thenReturn(0);

        TeamRunView view = projector.project(RUN_ID);

        assertEquals(TeamRunStatus.RUNNING, view.status());
        assertEquals("{\"revision\":2}", view.metadata());
        assertEquals(TeamTaskStatus.PENDING, view.tasks().getFirst().status());
        assertEquals(new TeamRunView.Progress(1, 0, 0, 0, 0), view.progress());
        verify(taskMapper, times(2)).selectList(any());
        verify(runMapper, times(1)).update(isNull(), any());
    }

    @Test
    void finalizingProjectionMergesOutcomeIntoMetadataObject() {
        String originalMetadata = "{\"traceId\":\"a\",\"nested\":{\"kept\":true}}";
        when(runMapper.selectById(RUN_ID)).thenReturn(run(TeamRunStatus.RUNNING, originalMetadata));
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(TeamTaskStatus.COMPLETED), task(TeamTaskStatus.FAILED)));
        when(runMapper.update(isNull(), any())).thenReturn(1);

        TeamRunView view = projector.project(RUN_ID);

        assertEquals(TeamRunStatus.FINALIZING, view.status());
        assertTrue(view.metadata().contains("\"traceId\":\"a\""));
        assertTrue(view.metadata().contains("\"nested\""));
        assertTrue(view.metadata().contains("\"projectedOutcome\":\"partial\""));

        ArgumentCaptor<LambdaUpdateWrapper<TeamRunEntity>> captor = updateCaptor();
        verify(runMapper).update(isNull(), captor.capture());
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().values().stream()
                .map(String::valueOf).anyMatch(value -> value.contains("projectedOutcome")));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(originalMetadata));
    }

    @Test
    void planningRunKeepsPlanningWhenTaskIsCreated() {
        when(runMapper.selectById(RUN_ID)).thenReturn(run(TeamRunStatus.PLANNING, null));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(TeamTaskStatus.PENDING)));

        TeamRunView view = projector.project(RUN_ID);

        assertEquals(TeamRunStatus.PLANNING, view.status());
        verify(runMapper, never()).update(isNull(), any());
    }

    @Test
    void nullAndMissingRunsAreSafe() {
        assertNull(projector.project(null));
        verify(runMapper, never()).selectById(any());

        when(runMapper.selectById(RUN_ID)).thenReturn(null);
        assertNull(projector.project(RUN_ID));
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    void projectionFailureIsLoggedAndSwallowed() {
        when(runMapper.selectById(RUN_ID)).thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> assertNull(projector.project(RUN_ID)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<TeamRunEntity>> updateCaptor() {
        return ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
    }

    private TeamRunEntity run(String status, String metadata) {
        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(10L);
        run.setWorkspaceId(30L);
        run.setLeadAgentId(40L);
        run.setLeadConversationId("conversation");
        run.setTitle("Research");
        run.setObjective("Research the topic");
        run.setStatus(status);
        run.setMetadata(metadata);
        return run;
    }

    private TeamTaskEntity task(String status) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setRunId(RUN_ID);
        task.setStatus(status);
        return task;
    }
}
