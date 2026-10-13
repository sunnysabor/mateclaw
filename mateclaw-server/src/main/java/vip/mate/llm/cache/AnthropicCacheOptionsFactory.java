package vip.mate.llm.cache;

import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** Maps application cache settings to the Spring AI 2 Anthropic cache contract. */
@Component
public class AnthropicCacheOptionsFactory {

    private static final int CHARS_PER_TOKEN_HEURISTIC = 4;

    private final CacheProperties props;

    public AnthropicCacheOptionsFactory(CacheProperties props) {
        this.props = props;
    }

    public AnthropicCacheOptions build() {
        if (!props.isEnabled()) {
            return AnthropicCacheOptions.disabled();
        }

        AnthropicCacheStrategy strategy = props.isIncludeToolsBlock()
                ? AnthropicCacheStrategy.CONVERSATION_HISTORY
                : AnthropicCacheStrategy.SYSTEM_ONLY;

        AnthropicCacheTtl ttl = (props.resolveCacheTtl() == CacheTtl.EXTENDED_1H)
                ? AnthropicCacheTtl.ONE_HOUR
                : AnthropicCacheTtl.FIVE_MINUTES;

        Map<MessageType, AnthropicCacheTtl> ttlMap = new EnumMap<>(MessageType.class);
        ttlMap.put(MessageType.SYSTEM, ttl);
        ttlMap.put(MessageType.USER, ttl);

        Map<MessageType, Integer> minLenMap = new EnumMap<>(MessageType.class);
        int sysMinChars = Math.max(256, props.getMinPromptTokens() * CHARS_PER_TOKEN_HEURISTIC / 8);
        minLenMap.put(MessageType.SYSTEM, sysMinChars);

        return AnthropicCacheOptions.builder()
                .strategy(strategy)
                .messageTypeTtl(ttlMap)
                .messageTypeMinContentLengths(minLenMap)
                .multiBlockSystemCaching(false)
                .build();
    }
}
