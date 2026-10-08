package com.interviewmind;

import com.interviewmind.memory.InterviewMemoryStore;
import com.interviewmind.memory.dto.InterviewMemory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * InterviewMemoryStore 的确定性测试桩：
 * <ul>
 *   <li>saveAll：记录写入供断言，保证普通 mvn test 不产生任何真实 Embedding / PgVector 调用；</li>
 *   <li>search：模拟 RAG 读取（按 resumeId 过滤种子数据、记录调用次数与参数、可注入异常），
 *       用于验证“单次检索（禁止 N+1）”与“检索失败回退”。</li>
 * </ul>
 * 由 InterviewSessionIntegrationTest、InterviewMemoryIntegrationTest、
 * InterviewRagIntegrationTest、InterviewQuestionIntegrationTest 共同导入
 * （import 集相同则共享同一个 Spring 上下文，测试间通过 clear() 隔离）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeInterviewMemoryStoreConfig {

    /** 一次 search 调用的参数快照 */
    public record SearchCall(String query, Long resumeId, int topK) {
    }

    @Bean
    @Primary
    InterviewMemoryStore testInterviewMemoryStore() {
        return new CapturingMemoryStore();
    }

    public static class CapturingMemoryStore implements InterviewMemoryStore {

        private final List<InterviewMemory> saved = Collections.synchronizedList(new ArrayList<>());
        private final List<SearchCall> searchCalls = Collections.synchronizedList(new ArrayList<>());

        /** RAG 检索的种子数据（模拟向量库中已有的历史记忆） */
        private volatile List<InterviewMemory> searchable = List.of();

        private volatile RuntimeException failure;
        private volatile RuntimeException searchFailure;

        @Override
        public void saveAll(List<InterviewMemory> memories) {
            if (failure != null) {
                throw failure;
            }
            saved.addAll(memories);
        }

        @Override
        public List<InterviewMemory> search(String query, Long resumeId, int topK) {
            searchCalls.add(new SearchCall(query, resumeId, topK));
            if (searchFailure != null) {
                throw searchFailure;
            }
            // 模拟 Spring AI metadata filter：只返回当前简历的历史记忆
            return searchable.stream()
                    .filter(memory -> resumeId != null && resumeId.equals(memory.resumeId()))
                    .toList();
        }

        public List<InterviewMemory> saved() {
            synchronized (saved) {
                return List.copyOf(saved);
            }
        }

        public List<SearchCall> searchCalls() {
            synchronized (searchCalls) {
                return List.copyOf(searchCalls);
            }
        }

        /** 注入 RAG 检索可返回的历史记忆 */
        public void seedSearchable(List<InterviewMemory> memories) {
            this.searchable = memories == null ? List.of() : List.copyOf(memories);
        }

        public void clear() {
            saved.clear();
            searchCalls.clear();
            searchable = List.of();
            failure = null;
            searchFailure = null;
        }

        public void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        public void noFail() {
            this.failure = null;
        }

        /** 注入检索异常（验证 RAG 故障隔离：出题回退纯简历模式） */
        public void searchFailWith(RuntimeException searchFailure) {
            this.searchFailure = searchFailure;
        }

        public void searchNoFail() {
            this.searchFailure = null;
        }

    }

}
