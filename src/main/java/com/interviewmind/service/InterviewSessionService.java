package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.InterviewEvaluationAiParser;
import com.interviewmind.ai.dto.InterviewEvaluationAiResult;
import com.interviewmind.ai.dto.QuestionEvaluationItem;
import com.interviewmind.ai.dto.QuestionItem;
import com.interviewmind.dto.InterviewAnswerRequest;
import com.interviewmind.dto.InterviewAnswerResponse;
import com.interviewmind.dto.InterviewCurrentQuestionResponse;
import com.interviewmind.dto.InterviewFinishResponse;
import com.interviewmind.dto.InterviewQuestionEvaluationResponse;
import com.interviewmind.dto.InterviewQuestionResponse;
import com.interviewmind.dto.InterviewReportResponse;
import com.interviewmind.dto.InterviewStartRequest;
import com.interviewmind.dto.InterviewStartResponse;
import com.interviewmind.entity.InterviewAnswer;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.entity.InterviewReport;
import com.interviewmind.entity.InterviewSession;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.InterviewAnswerMapper;
import com.interviewmind.memory.InterviewMemoryService;
import com.interviewmind.mapper.InterviewQuestionMapper;
import com.interviewmind.mapper.InterviewReportMapper;
import com.interviewmind.mapper.InterviewSessionMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 模拟面试编排：开始面试（生成题目快照）→ 逐题作答 → AI 整体评价 → 报告落库。
 * <p>
 * 事务边界：只有纯数据库写入使用短事务（创建会话+题目、保存回答+推进进度、
 * 保存评价+报告+完成会话）；LLM 调用全部在事务之外，绝不放入长事务。
 * <p>
 * 评价策略：用户逐题作答期间不调用 LLM（保存 answer_text 即可），
 * 整场结束后一次性调用 Interview Evaluation AI 分析全部 20 题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final InterviewQuestionService interviewQuestionService;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final InterviewReportMapper reportMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final InterviewEvaluationAiParser interviewEvaluationAiParser;
    private final InterviewMemoryService interviewMemoryService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 开始面试：复用 {@link InterviewQuestionService} 生成 20 道题（内部完成
     * 简历 404 / 未解析 409 / profile 409 校验，LLM 调用在事务外），
     * 然后短事务写入会话与题目快照。
     */
    public InterviewStartResponse start(InterviewStartRequest request) {
        if (request == null || request.resumeId() == null) {
            throw new BusinessException(400, "resumeId 不能为空");
        }
        Long resumeId = request.resumeId();
        InterviewQuestionResponse generated = interviewQuestionService.generate(resumeId);
        List<QuestionItem> questions = generated.questions();

        LocalDateTime now = LocalDateTime.now();
        InterviewSession session = transactionTemplate.execute(txStatus -> {
            InterviewSession entity = new InterviewSession();
            entity.setResumeId(resumeId);
            entity.setStatus(InterviewSession.STATUS_IN_PROGRESS);
            entity.setTotalQuestions(questions.size());
            entity.setCurrentQuestion(1);
            entity.setStartedAt(now);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            sessionMapper.insert(entity);

            for (int i = 0; i < questions.size(); i++) {
                QuestionItem item = questions.get(i);
                InterviewQuestion row = new InterviewQuestion();
                row.setSessionId(entity.getId());
                row.setQuestionNo(i + 1);
                row.setQuestion(item.question());
                row.setType(item.type());
                row.setDifficulty(item.difficulty());
                row.setKnowledgePoint(item.knowledgePoint());
                row.setReason(item.reason());
                row.setReferenceAnswer(item.referenceAnswer());
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                questionMapper.insert(row);
            }
            return entity;
        });

        log.info("模拟面试开始: sessionId={}, resumeId={}, questions={}",
                session.getId(), resumeId, questions.size());
        InterviewQuestion first = findQuestion(session.getId(), session.getCurrentQuestion());
        return new InterviewStartResponse(session.getId(), session.getStatus(),
                session.getTotalQuestions(), toCurrentQuestion(session, first));
    }

    /**
     * 获取当前待作答题目（会话恢复）：进度保存在数据库，刷新页面即可恢复。
     * 故意不返回 referenceAnswer / reason。
     */
    public InterviewCurrentQuestionResponse currentQuestion(Long sessionId) {
        InterviewSession session = requireSession(sessionId);
        requireInProgress(session);
        if (session.getCurrentQuestion() > session.getTotalQuestions()) {
            throw new BusinessException(409, "题目已全部作答，请调用 POST /api/interviews/"
                    + sessionId + "/finish 结束面试");
        }
        InterviewQuestion question = findQuestion(sessionId, session.getCurrentQuestion());
        return toCurrentQuestion(session, question);
    }

    /**
     * 提交当前题回答：保存 answer_text + 推进 current_question（短事务，不调用 LLM）。
     * 最后一题提交后 interviewFinished=true，由前端另行调用 finish。
     */
    public InterviewAnswerResponse submitAnswer(Long sessionId, InterviewAnswerRequest request) {
        InterviewSession session = requireSession(sessionId);
        requireInProgress(session);

        int current = session.getCurrentQuestion();
        if (current > session.getTotalQuestions()) {
            throw new BusinessException(409, "所有题目已作答，请调用 POST /api/interviews/"
                    + sessionId + "/finish 结束面试");
        }
        InterviewQuestion question = findQuestion(sessionId, current);
        if (request == null || request.answer() == null || request.answer().isBlank()) {
            throw new BusinessException(400, "回答内容不能为空");
        }

        Long questionId = question.getId();
        InterviewAnswer existing = answerMapper.selectOne(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getQuestionId, questionId));
        if (existing != null) {
            throw new BusinessException(409, "第 " + current + " 题已作答，不可重复提交");
        }

        int next = current + 1;
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.execute(txStatus -> {
            InterviewAnswer answer = new InterviewAnswer();
            answer.setQuestionId(questionId);
            answer.setAnswerText(request.answer().trim());
            answer.setCreatedAt(now);
            answer.setUpdatedAt(now);
            answerMapper.insert(answer);

            session.setCurrentQuestion(next);
            session.setUpdatedAt(now);
            sessionMapper.updateById(session);
            return null;
        });

        boolean finished = next > session.getTotalQuestions();
        InterviewCurrentQuestionResponse nextQuestion = finished
                ? null
                : toCurrentQuestion(session, findQuestion(sessionId, next));
        return new InterviewAnswerResponse(sessionId, finished, nextQuestion);
    }

    /**
     * 结束面试：校验全部作答 → 构建结构化评价输入 → LLM（事务外）→
     * 短事务补充 20 个回答的评分、写入报告、置为 COMPLETED。
     * 对已完成会话返回 409（幂等语义由前端根据 409 + 报告查询自行处理）。
     */
    public InterviewFinishResponse finish(Long sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (InterviewSession.STATUS_COMPLETED.equals(session.getStatus())) {
            throw new BusinessException(409, "面试已完成，不可重复结束: " + sessionId);
        }
        requireInProgress(session);

        List<InterviewQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .orderByAsc(InterviewQuestion::getQuestionNo));
        if (questions.isEmpty()) {
            throw new BusinessException(500, "面试题目不存在: sessionId=" + sessionId);
        }
        List<Long> questionIds = questions.stream().map(InterviewQuestion::getId).toList();
        Map<Long, InterviewAnswer> answers = answerMapper.selectList(
                        new LambdaQueryWrapper<InterviewAnswer>()
                                .in(InterviewAnswer::getQuestionId, questionIds))
                .stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, a -> a, (a, b) -> a));
        List<Integer> missing = questions.stream()
                .filter(q -> !answers.containsKey(q.getId()))
                .map(InterviewQuestion::getQuestionNo)
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(409, "还有题目未作答，无法结束面试: 第 "
                    + String.join("、", missing.stream().map(String::valueOf).toList()) + " 题");
        }

        ResumeProfile profile = resumeProfileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfile>()
                        .eq(ResumeProfile::getResumeId, session.getResumeId()));
        if (profile == null || profile.getRawText() == null || profile.getRawText().isBlank()) {
            throw new BusinessException(409, "简历解析结果不存在: resumeId=" + session.getResumeId());
        }

        // LLM 调用在任何事务之外
        String evaluationInput = buildEvaluationInput(profile, questions, answers);
        long startMillis = System.currentTimeMillis();
        InterviewEvaluationAiResult result = interviewEvaluationAiParser.evaluate(evaluationInput);
        long costMillis = System.currentTimeMillis() - startMillis;
        log.info("AI 面试评价完成: sessionId={}, cost={}ms", sessionId, costMillis);

        List<QuestionEvaluationItem> evaluations = normalizeEvaluations(result, questions.size());
        int totalScore = resolveTotalScore(result, evaluations);

        LocalDateTime now = LocalDateTime.now();
        InterviewReport saved = transactionTemplate.execute(txStatus -> {
            for (int i = 0; i < questions.size(); i++) {
                QuestionEvaluationItem eval = evaluations.get(i);
                InterviewAnswer answer = answers.get(questions.get(i).getId());
                answer.setScore(eval.score());
                answer.setAnalysis(eval.analysis());
                answer.setWeakPoints(toJson(eval.weakPoints()));
                answer.setSuggestions(toJson(eval.suggestions()));
                answer.setUpdatedAt(now);
                answerMapper.updateById(answer);
            }

            InterviewReport report = new InterviewReport();
            report.setSessionId(sessionId);
            report.setTotalScore(totalScore);
            report.setSummary(result.summary());
            report.setStrengths(toJson(result.strengths()));
            report.setWeakPoints(toJson(result.weakPoints()));
            report.setSuggestions(toJson(result.suggestions()));
            report.setCreatedAt(now);
            report.setUpdatedAt(now);
            reportMapper.insert(report);

            session.setStatus(InterviewSession.STATUS_COMPLETED);
            session.setFinishedAt(now);
            session.setUpdatedAt(now);
            sessionMapper.updateById(session);
            return report;
        });

        // 事务外：InterviewEvaluationAiResult → Interview Memory → Embedding → PgVectorStore。
        // 传入归一化后的逐题评价（与落库分数一致）；写入失败由 Service 兜底记日志，
        // 不回滚已完成的面试
        interviewMemoryService.indexMemories(session.getResumeId(), sessionId, questions,
                new InterviewEvaluationAiResult(saved.getTotalScore(), result.summary(),
                        result.strengths(), result.weakPoints(), result.suggestions(),
                        evaluations));

        InterviewReportResponse reportResponse = new InterviewReportResponse(
                sessionId,
                saved.getTotalScore(),
                saved.getSummary(),
                toList(saved.getStrengths()),
                toList(saved.getWeakPoints()),
                toList(saved.getSuggestions()),
                session.getFinishedAt(),
                evaluations.stream()
                        .map(e -> new InterviewQuestionEvaluationResponse(
                                e.questionNo(), e.score(), e.analysis(),
                                e.weakPoints(), e.suggestions()))
                        .toList());
        return new InterviewFinishResponse(sessionId, session.getStatus(), reportResponse);
    }

    // ---------- 评价结果归一化 ----------

    /** 按题号排序并截断/重编号到期望数量，分数统一钳制到 0-10，空数组归一为 []。 */
    private List<QuestionEvaluationItem> normalizeEvaluations(InterviewEvaluationAiResult result,
                                                              int expected) {
        List<QuestionEvaluationItem> raw = result.questionEvaluations();
        if (raw == null || raw.size() < expected) {
            throw new BusinessException(502, "AI 面试评价失败: 逐题评价数量不足");
        }
        List<QuestionEvaluationItem> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt(
                item -> item.questionNo() == null ? Integer.MAX_VALUE : item.questionNo()));

        boolean sequential = IntStream.range(0, expected)
                .allMatch(i -> sorted.get(i).questionNo() != null
                        && sorted.get(i).questionNo() == i + 1);
        if (!sequential) {
            log.warn("AI 逐题评价 questionNo 不连续，按顺序重新编号");
        }

        List<QuestionEvaluationItem> normalized = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            QuestionEvaluationItem item = sorted.get(i);
            Integer score = item.score();
            if (score == null) {
                log.warn("AI 逐题评价缺少 score: questionNo={}", item.questionNo());
            }
            normalized.add(new QuestionEvaluationItem(
                    sequential ? item.questionNo() : i + 1,
                    clampScore(score),
                    item.analysis(),
                    nullSafe(item.weakPoints()),
                    nullSafe(item.suggestions())));
        }
        return normalized;
    }

    /** totalScore：模型给的 0-100 合法值优先，否则以后端计算（均分 × 10）为准。 */
    private int resolveTotalScore(InterviewEvaluationAiResult result,
                                  List<QuestionEvaluationItem> evaluations) {
        Integer aiTotal = result.totalScore();
        if (aiTotal != null && aiTotal >= 0 && aiTotal <= 100) {
            return aiTotal;
        }
        double average = evaluations.stream()
                .mapToInt(QuestionEvaluationItem::score)
                .average()
                .orElse(0);
        return (int) Math.round(average * 10);
    }

    private static int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(10, score));
    }

    // ---------- 评价输入构建 ----------

    /** Service 层构建结构化评价输入：简历信息 + 20 道题 + 用户回答 + 参考答案。 */
    private String buildEvaluationInput(ResumeProfile profile,
                                        List<InterviewQuestion> questions,
                                        Map<Long, InterviewAnswer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("【简历信息】\n");
        sb.append("姓名：").append(profile.getName() == null || profile.getName().isBlank()
                ? "未知" : profile.getName()).append('\n');
        sb.append("简历原文：\n").append(profile.getRawText()).append("\n\n");

        sb.append("【面试题目与用户回答】\n");
        for (InterviewQuestion question : questions) {
            sb.append("第").append(question.getQuestionNo()).append("题（")
                    .append(question.getType()).append(" / ").append(question.getDifficulty());
            if (question.getKnowledgePoint() != null && !question.getKnowledgePoint().isBlank()) {
                sb.append("，知识点：").append(question.getKnowledgePoint());
            }
            sb.append("）\n");
            sb.append("问题：").append(question.getQuestion()).append('\n');
            sb.append("用户回答：")
                    .append(answers.get(question.getId()).getAnswerText()).append('\n');
            String reference = question.getReferenceAnswer() == null
                    || question.getReferenceAnswer().isBlank()
                    ? "无" : question.getReferenceAnswer();
            sb.append("参考答案：").append(reference).append("\n\n");
        }
        return sb.toString();
    }

    // ---------- 基础校验与转换 ----------

    private InterviewSession requireSession(Long sessionId) {
        InterviewSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(404, "面试会话不存在: " + sessionId);
        }
        return session;
    }

    private void requireInProgress(InterviewSession session) {
        if (!InterviewSession.STATUS_IN_PROGRESS.equals(session.getStatus())) {
            throw new BusinessException(409, "当前面试状态不可操作: " + session.getStatus());
        }
    }

    private InterviewQuestion findQuestion(Long sessionId, Integer questionNo) {
        InterviewQuestion question = questionMapper.selectOne(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .eq(InterviewQuestion::getQuestionNo, questionNo));
        if (question == null) {
            throw new BusinessException(500, "面试题目不存在: sessionId=" + sessionId
                    + ", questionNo=" + questionNo);
        }
        return question;
    }

    /** 面试过程中的题目视图：不含 referenceAnswer / reason。 */
    private InterviewCurrentQuestionResponse toCurrentQuestion(InterviewSession session,
                                                               InterviewQuestion question) {
        return new InterviewCurrentQuestionResponse(
                session.getId(),
                question.getQuestionNo(),
                session.getTotalQuestions(),
                question.getQuestion(),
                question.getType(),
                question.getDifficulty(),
                question.getKnowledgePoint());
    }

    private String toJson(List<String> list) {
        return list == null ? "[]" : objectMapper.writeValueAsString(list);
    }

    private List<String> toList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("JSONB 解析失败: {}", json, e);
            return List.of();
        }
    }

    private static List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : list;
    }

}
