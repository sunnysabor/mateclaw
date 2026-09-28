package vip.mate.decision.config;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vip.mate.decision.api.DecisionMode;
import vip.mate.decision.api.DecisionType;
import java.util.EnumMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "mate.decision")
public class DecisionProperties {
    private DecisionMode mode = DecisionMode.SHADOW;
    private Map<DecisionType, DecisionMode> scenarios = new EnumMap<>(DecisionType.class);
    private String provider = "rule";
    private double confidenceThreshold = 0.8;
    private long timeoutMs = 250;
    private int providerThreads = 4;
    private int shadowThreads = 2;
    private int queueCapacity = 64;
    private int retentionDays = 30;
    private int retentionBatchSize = 500;
    public DecisionMode modeFor(DecisionType type) { return scenarios.getOrDefault(type, mode); }
    public void validate() {
        if (mode == null || scenarios == null || provider == null || !provider.matches("[A-Za-z0-9_.:-]{1,80}")
                || !Double.isFinite(confidenceThreshold) || confidenceThreshold < 0 || confidenceThreshold > 1
                || timeoutMs < 1 || timeoutMs > 60000 || providerThreads < 1 || providerThreads > 32
                || shadowThreads < 1 || shadowThreads > 16 || queueCapacity < 1 || queueCapacity > 10000
                || retentionDays < 1 || retentionBatchSize < 1 || retentionBatchSize > 10000)
            throw new IllegalArgumentException("Invalid decision configuration");
    }
}
