package vip.mate.memory.spi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.memory.event.MemoryWriteEvent;

/**
 * Bridges Spring memory-write events into the MemoryProvider SPI.
 * <p>
 * Several providers maintain lightweight derived state from canonical memory
 * files. This listener intentionally runs synchronously so a successful
 * saveFile/saveMemoryFile call has also refreshed deterministic projections
 * (notably the fact store) before the HTTP/tool caller reloads them. Heavy
 * downstream work such as SOUL evolution stays on its own @Async event listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryWriteEventDispatcher {

    private final MemoryManager memoryManager;

    @EventListener
    public void onMemoryWrite(MemoryWriteEvent event) {
        if (event == null || event.agentId() == null) {
            return;
        }
        try {
            memoryManager.onMemoryWrite(event.agentId(), event.target(),
                    event.action(), event.content(), event.ownerKey(), event.scope());
        } catch (Exception e) {
            log.debug("[Memory] MemoryWriteEvent SPI dispatch failed for agent={}, target={}: {}",
                    event.agentId(), event.target(), e.getMessage());
        }
    }
}
