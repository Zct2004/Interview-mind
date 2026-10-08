package com.interviewmind.vector;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * PgVectorStore 的 Embedding 批量策略配置（只影响 Embedding 链路，不触碰任何 Chat 配置）。
 * <p>
 * 替换 PgVectorStoreAutoConfiguration 的默认 TokenCountBatchingStrategy
 * （该 bean 为 @ConditionalOnMissingBean，自定义 BatchingStrategy 会自动接管）：
 * 在原有按 token 分批之上，再按条数分批——DashScope qwen3.7-text-embedding
 * 单次请求最多 20 条输入，超出返回
 * {@code 400: batch size is invalid, it should not be larger than 20}（实测）。
 */
@Configuration(proxyBeanMethods = false)
public class VectorStoreConfig {

    /** DashScope Embedding 单请求批量条数上限（实测值） */
    static final int MAX_EMBEDDING_BATCH_SIZE = 20;

    @Bean
    BatchingStrategy dashScopeBatchingStrategy() {
        BatchingStrategy tokenBatching = new TokenCountBatchingStrategy();
        return documents -> tokenBatching.batch(documents).stream()
                .flatMap(tokenBatch -> chunks(tokenBatch, MAX_EMBEDDING_BATCH_SIZE).stream())
                .toList();
    }

    private static <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            result.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return result;
    }

}
