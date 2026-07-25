package vip.mate.team.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.common.result.R;
import vip.mate.team.model.TeamTaskCreateCommand;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.service.TeamAnnounceService;
import vip.mate.team.service.TeamDispatchService;
import vip.mate.team.service.TeamEventChannel;
import vip.mate.team.service.TeamService;
import vip.mate.team.service.TeamTaskService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that service-layer validation verdicts (IllegalArgumentException /
 * IllegalStateException) surface through the R envelope as readable messages
 * instead of escaping to the global catch-all 500 handler.
 */
@ExtendWith(MockitoExtension.class)
class TeamControllerTest {

    private static final Long TEAM_ID = 1L;
    private static final Long TASK_ID = 100L;

    @Mock private TeamService teamService;
    @Mock private TeamTaskService taskService;
    @Mock private TeamDispatchService dispatchService;
    @Mock private TeamAnnounceService announceService;
    @Mock private TeamEventChannel eventChannel;
    @Mock private AgentMapper agentMapper;

    private TeamController controller;

    @BeforeEach
    void setUp() {
        controller = new TeamController(teamService, taskService, dispatchService,
                announceService, eventChannel, agentMapper);
    }

    private TeamTaskEntity task(Long teamId, String status) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(TASK_ID);
        task.setTeamId(teamId);
        task.setTaskNumber(7);
        task.setStatus(status);
        task.setSubject("subject");
        return task;
    }

    // ==================== team / membership ====================

    @Test
    void createTeamSurfacesMembershipConflictAsReadableFailure() {
        when(teamService.createTeam(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "agent 5 already belongs to team 2; an agent can join only one team"));
        TeamController.CreateTeamRequest req = new TeamController.CreateTeamRequest();
        req.setName("t");
        req.setLeadAgentId(5L);

        R<TeamController.TeamVO> r = controller.create(req, null);

        assertEquals(500, r.getCode());
        assertEquals("agent 5 already belongs to team 2; an agent can join only one team", r.getMsg());
    }

    @Test
    void updateTeamSurfacesUnknownTeamAsReadableFailure() {
        when(teamService.updateTeam(eq(TEAM_ID), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("team not found: " + TEAM_ID));

        R<TeamController.TeamVO> r = controller.update(TEAM_ID, new TeamController.UpdateTeamRequest());

        assertEquals(500, r.getCode());
        assertEquals("team not found: 1", r.getMsg());
    }

    @Test
    void addMemberSurfacesValidationAsReadableFailure() {
        doThrow(new IllegalArgumentException("agent is already the team lead"))
                .when(teamService).addMember(TEAM_ID, 5L, "member");
        TeamController.MemberRequest req = new TeamController.MemberRequest();
        req.setAgentId(5L);
        req.setRole("member");

        R<Void> r = controller.addMember(TEAM_ID, req);

        assertEquals(500, r.getCode());
        assertEquals("agent is already the team lead", r.getMsg());
    }

    @Test
    void removeMemberSurfacesLeadProtectionAsReadableFailure() {
        doThrow(new IllegalArgumentException("cannot remove the team lead; delete the team instead"))
                .when(teamService).removeMember(TEAM_ID, 5L);

        R<Void> r = controller.removeMember(TEAM_ID, 5L);

        assertEquals(500, r.getCode());
        assertEquals("cannot remove the team lead; delete the team instead", r.getMsg());
    }

    // ==================== task board ====================

    @Test
    void createTaskSurfacesUnknownAssigneeAsReadableFailure() {
        when(taskService.createTask(any(TeamTaskCreateCommand.class)))
                .thenThrow(new IllegalArgumentException("assignee 9 is not a member of this team"));
        TeamController.CreateTaskRequest req = new TeamController.CreateTaskRequest();
        req.setSubject("do the thing");
        req.setAssigneeAgentId(9L);

        R<TeamController.TaskVO> r = controller.createTask(TEAM_ID, req, null);

        assertEquals(500, r.getCode());
        assertEquals("assignee 9 is not a member of this team", r.getMsg());
        verify(eventChannel, never()).publishTaskEvent(any(), anyString(), any());
        verify(dispatchService, never()).requestDispatch(anyLong());
    }

    @Test
    void approveRejectsTaskFromAnotherTeamsBoard() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(2L, "in_review"));

        R<TeamController.TaskVO> r = controller.approve(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
        verify(taskService, never()).approveTask(anyLong());
    }

    @Test
    void approveSurfacesWrongStatusAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "pending"));
        when(taskService.approveTask(TASK_ID))
                .thenThrow(new IllegalStateException("task #7 is not awaiting review"));

        R<TeamController.TaskVO> r = controller.approve(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("task #7 is not awaiting review", r.getMsg());
    }

    @Test
    void approveHappyPathStillReturnsTask() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "in_review"));
        when(taskService.approveTask(TASK_ID)).thenReturn(List.of(200L));

        R<TeamController.TaskVO> r = controller.approve(TEAM_ID, TASK_ID, null);

        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        verify(dispatchService).requestDispatch(TEAM_ID);
    }

    @Test
    void rejectSurfacesWrongStatusAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "pending"));
        doThrow(new IllegalStateException("task #7 is not awaiting review"))
                .when(taskService).rejectTask(TASK_ID, null);

        R<TeamController.TaskVO> r = controller.reject(TEAM_ID, TASK_ID, null, null);

        assertEquals(500, r.getCode());
        assertEquals("task #7 is not awaiting review", r.getMsg());
        verify(announceService, never()).announceTaskSettled(any());
    }

    @Test
    void retrySurfacesTaskFromAnotherTeamAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(2L, "failed"));

        R<TeamController.TaskVO> r = controller.retry(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
        verify(taskService, never()).retryTask(anyLong());
    }

    @Test
    void retryOnNonRetryableStatusKeepsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "done"));
        when(taskService.retryTask(TASK_ID)).thenReturn(false);

        R<TeamController.TaskVO> r = controller.retry(TEAM_ID, TASK_ID, null);

        assertEquals(500, r.getCode());
        assertEquals("only failed or stale tasks can be retried", r.getMsg());
    }

    @Test
    void cancelSurfacesTerminalTaskAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(task(TEAM_ID, "done"));
        when(taskService.cancelTask(TASK_ID, null))
                .thenThrow(new IllegalStateException("task #7 is already terminal"));

        R<TeamController.TaskVO> r = controller.cancel(TEAM_ID, TASK_ID, null, null);

        assertEquals(500, r.getCode());
        assertEquals("task #7 is already terminal", r.getMsg());
        verify(dispatchService, never()).interruptRun(any());
    }

    @Test
    void commentSurfacesUnknownTaskAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(null);
        TeamController.CommentRequest req = new TeamController.CommentRequest();
        req.setContent("hi");

        R<Void> r = controller.comment(TEAM_ID, TASK_ID, req, null);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
        verify(taskService, never()).addComment(anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void getTaskSurfacesUnknownTaskAsReadableFailure() {
        when(taskService.getTask(TASK_ID)).thenReturn(null);

        R<TeamController.TaskDetailVO> r = controller.getTask(TEAM_ID, TASK_ID);

        assertEquals(500, r.getCode());
        assertEquals("task not found on this team's board", r.getMsg());
    }
}
