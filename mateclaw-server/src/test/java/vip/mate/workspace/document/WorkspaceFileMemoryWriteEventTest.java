package vip.mate.workspace.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.memory.event.MemoryWritePublisher;
import vip.mate.memory.identity.MemoryScope;
import vip.mate.workspace.document.event.WorkspaceFileChangedEvent;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import vip.mate.workspace.document.repository.WorkspaceFileMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the canonical memory-write bus. WorkspaceFileService
 * is the single source of MemoryWriteEvent publication; higher-level writers
 * should only save files through it, not publish duplicate events themselves.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceFileMemoryWriteEventTest {

    @Mock private WorkspaceFileMapper fileMapper;
    @Mock private ApplicationEventPublisher fileEvents;
    @Mock private ApplicationEventPublisher memoryEvents;

    private WorkspaceFileService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceFileService(fileMapper, fileEvents, new MemoryWritePublisher(memoryEvents));
    }

    @Test
    @DisplayName("saveFile(MEMORY.md) publishes one canonical TEAM MemoryWriteEvent with full content")
    void saveFileCanonicalMemoryPublishesOnce() {
        when(fileMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        service.saveFile(1000000001L, "MEMORY.md", "## Facts\n- stable");

        ArgumentCaptor<WorkspaceFileChangedEvent> changed =
                ArgumentCaptor.forClass(WorkspaceFileChangedEvent.class);
        verify(fileEvents).publishEvent(changed.capture());
        assertThat(changed.getValue().filename()).isEqualTo("MEMORY.md");

        ArgumentCaptor<MemoryWriteEvent> memory =
                ArgumentCaptor.forClass(MemoryWriteEvent.class);
        verify(memoryEvents).publishEvent(memory.capture());
        assertThat(memory.getValue().agentId()).isEqualTo(1000000001L);
        assertThat(memory.getValue().target()).isEqualTo("MEMORY.md");
        assertThat(memory.getValue().action()).isEqualTo("create");
        assertThat(memory.getValue().content()).isEqualTo("## Facts\n- stable");
        assertThat(memory.getValue().scope()).isEqualTo(MemoryScope.TEAM);
        assertThat(memory.getValue().ownerKey()).isNull();
    }

    @Test
    @DisplayName("saveMemoryFile(structured/*.md) publishes one owner-scoped PERSONAL MemoryWriteEvent")
    void savePersonalStructuredPublishesOwnerScopedEvent() {
        when(fileMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        service.saveMemoryFile(1000000001L, "structured/user.md",
                "## preferred_language\n简体中文", "user:jerry");

        ArgumentCaptor<MemoryWriteEvent> memory =
                ArgumentCaptor.forClass(MemoryWriteEvent.class);
        verify(memoryEvents).publishEvent(memory.capture());
        assertThat(memory.getValue().target()).isEqualTo("structured/user.md");
        assertThat(memory.getValue().scope()).isEqualTo(MemoryScope.PERSONAL);
        assertThat(memory.getValue().ownerKey()).isEqualTo("user:jerry");
        assertThat(memory.getValue().content()).contains("preferred_language");
    }

    @Test
    @DisplayName("non-canonical workspace files still invalidate agent cache but do not publish MemoryWriteEvent")
    void nonCanonicalDoesNotPublishMemoryWrite() {
        when(fileMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        service.saveFile(1000000001L, "PROFILE.md", "## Identity\nMate");

        verify(fileEvents).publishEvent(any(WorkspaceFileChangedEvent.class));
        verify(memoryEvents, never()).publishEvent(any(MemoryWriteEvent.class));
    }

    @Test
    @DisplayName("existing MEMORY.md update publishes exactly one update event")
    void existingCanonicalUpdatePublishesOneUpdateEvent() {
        WorkspaceFileEntity existing = new WorkspaceFileEntity();
        existing.setId(1L);
        existing.setAgentId(1000000001L);
        existing.setFilename("MEMORY.md");
        existing.setContent("old");
        existing.setFileSize((long) "old".getBytes(StandardCharsets.UTF_8).length);
        existing.setScope(MemoryScope.TEAM);
        existing.setOwnerKey("");
        when(fileMapper.selectOne(any(), anyBoolean())).thenReturn(existing);

        service.saveFile(1000000001L, "MEMORY.md", "new");

        ArgumentCaptor<MemoryWriteEvent> memory =
                ArgumentCaptor.forClass(MemoryWriteEvent.class);
        verify(memoryEvents).publishEvent(memory.capture());
        assertThat(memory.getValue().action()).isEqualTo("update");
        assertThat(memory.getValue().content()).isEqualTo("new");
    }
}
