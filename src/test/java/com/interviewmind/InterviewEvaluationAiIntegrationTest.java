package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.interviewmind.config.MinioProperties;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.InterviewAnswer;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.entity.InterviewReport;
import com.interviewmind.entity.InterviewSession;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.InterviewAnswerMapper;
import com.interviewmind.mapper.InterviewQuestionMapper;
import com.interviewmind.mapper.InterviewReportMapper;
import com.interviewmind.mapper.InterviewSessionMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import com.jayway.jsonpath.JsonPath;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 LLM 全链路测试：MinIO 真实简历 → PDFBox → Spring AI 解析 → Spring AI 生成 20 题
 * → 逐题作答 → Spring AI 整场评价 → 报告落库。
 * <p>
 * 默认 mvn test 通过 surefire excludedGroups=ai 排除本类（不消耗 token）；
 * 运行 mvn -Pai-test test 时纳入执行，直接复用 application.yml 中已配置的
 * spring.ai.openai（不读环境变量、不改任何 AI 配置）。
 * 用户回答由测试以纯文本形式提交（模拟考生作答，不是伪造简历文件）。
 */
@Tag("ai")
@SpringBootTest
@AutoConfigureMockMvc
class InterviewEvaluationAiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluationAiIntegrationTest.class);

    /** 模拟考生的 20 条回答：有掌握的、有半懂的、有直接承认不会的，供 AI 客观评分 */
    private static final String[] ANSWERS = {
            "HashMap 默认长度16，用拉链法解决冲突，元素多时转红黑树。",
            "InnoDB 用 B+ 树做索引，叶子节点形成链表方便范围查询。",
            "RDB 是快照，AOF 是追加日志，AOF 更安全但文件更大。",
            "这个注解我记得是 @EnableAutoConfiguration，具体加载机制不太清楚。",
            "WebSocket 支持全双工，连接建立后可以服务端主动推送，HTTP 不行。",
            "看门狗会起一个后台线程，在锁过期前自动续期，防止业务没做完锁就失效了。",
            "我们给热点 Key 设置了随机过期时间，避免同时失效造成雪崩。",
            "请求先进 MQ 排队，消费者按自己的速度处理订单，削平数据库写入尖峰。",
            "Seata 的 AT 模式会记录 undo_log，回滚时按镜像反向补偿。",
            "Sentinel 热点限流是对某个具体参数值单独限流，比整体 QPS 限流更细。",
            "串口数据我们在帧头帧尾加了分隔符，缓冲区凑齐一帧才处理。",
            "回放倍速是前端发倍速指令，后端调整推送间隔实现的。",
            "跨域用的 iframe 加 postMessage，指定目标源并监听 message 事件。",
            "Plan-Act 负责拆解任务步骤，ReAct 负责单步的思考-行动-观察循环。",
            "工具注册到注册表里，模型输出工具名，框架按名字路由到对应实现。",
            "Redis 预扣减用 Lua 脚本保证原子性，扣失败直接返回，成功才落 MQ 异步同步 DB。",
            "锁粒度按车次+日期拆开，不同车次互不阻塞，比全局锁吞吐高很多。",
            "长期记忆用 JSON 文件存历史案例，下次遇到类似任务先检索出来注入上下文。",
            "我们会裁剪无关历史消息，限制最大推理轮次，超时就降级返回默认结果。",
            "Gateway 过滤器里解析 JWT，验签后把用户信息塞进 ThreadLocal 传给下游。",
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeMapper resumeMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ResumeProfileMapper resumeProfileMapper;

    @Autowired
    private InterviewSessionMapper sessionMapper;

    @Autowired
    private InterviewQuestionMapper questionMapper;

    @Autowired
    private InterviewAnswerMapper answerMapper;

    @Autowired
    private InterviewReportMapper reportMapper;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    @BeforeEach
    void resetData() {
        answerMapper.delete(new QueryWrapper<InterviewAnswer>());
        questionMapper.delete(new QueryWrapper<InterviewQuestion>());
        reportMapper.delete(new QueryWrapper<InterviewReport>());
        sessionMapper.delete(new QueryWrapper<InterviewSession>());
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    @Test
    void fullInterviewFlowWithRealLlmProducesCompleteReport() throws Exception {
        // 1. 上传真实简历并解析（真实简历 AI）
        long resumeId = uploadRealResume();
        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARSED"));
        ResumeProfile profile = resumeProfileMapper.selectOne(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId));
        assertThat(profile).isNotNull();
        assertThat(profile.getRawText()).isNotBlank();

        // 2. 开始面试（真实题目 AI）：创建会话 + 20 道题快照
        MvcResult startResult = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQuestions").value(20))
                .andExpect(jsonPath("$.data.firstQuestion.questionNo").value(1))
                .andReturn();
        String startBody = startResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long sessionId = JsonPath.parse(startBody).read("$.data.sessionId", Long.class);
        assertThat(questionMapper.selectList(
                new QueryWrapper<InterviewQuestion>().eq("session_id", sessionId))).hasSize(20);

        // 3. 逐题作答（不调用 LLM）
        for (int i = 0; i < 20; i++) {
            MvcResult answerResult = mockMvc.perform(
                            post("/api/interviews/{id}/answers", sessionId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"answer\":\"" + ANSWERS[i] + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String answerBody = answerResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
            boolean finished = JsonPath.parse(answerBody).read("$.data.interviewFinished", Boolean.class);
            assertThat(finished).isEqualTo(i == 19);
        }
        InterviewSession session = sessionMapper.selectById(sessionId);
        assertThat(session.getCurrentQuestion()).isEqualTo(21);
        assertThat(session.getStatus()).isEqualTo("IN_PROGRESS");

        // 4. 结束面试（真实评价 AI）
        long startMillis = System.currentTimeMillis();
        MvcResult finishResult = mockMvc.perform(post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andReturn();
        long costMillis = System.currentTimeMillis() - startMillis;
        log.info("真实 LLM 整场面试评价耗时: {}ms", costMillis);

        // 5. 断言报告结构
        String finishBody = finishResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Integer totalScore = JsonPath.parse(finishBody).read("$.data.report.totalScore", Integer.class);
        assertThat(totalScore).isBetween(0, 100);

        String summary = JsonPath.parse(finishBody).read("$.data.report.summary", String.class);
        assertThat(summary).isNotBlank();

        List<?> strengths = JsonPath.parse(finishBody).read("$.data.report.strengths", List.class);
        List<?> reportWeakPoints = JsonPath.parse(finishBody).read("$.data.report.weakPoints", List.class);
        List<?> reportSuggestions = JsonPath.parse(finishBody).read("$.data.report.suggestions", List.class);
        assertThat(strengths).isNotNull();
        assertThat(reportWeakPoints).isNotNull();
        assertThat(reportSuggestions).isNotNull();

        List<Map<String, Object>> evaluations = JsonPath.parse(finishBody)
                .read("$.data.report.questionEvaluations", List.class);
        // 数量 20、questionNo 1-20、每题 score 0-10
        assertThat(evaluations).hasSize(20);
        Set<Integer> questionNos = new HashSet<>();
        for (Map<String, Object> evaluation : evaluations) {
            questionNos.add((Integer) evaluation.get("questionNo"));
            assertThat((Integer) evaluation.get("score")).isBetween(0, 10);
            assertThat((String) evaluation.get("analysis")).isNotBlank();
            assertThat(evaluation.get("weakPoints")).isNotNull();
            assertThat(evaluation.get("suggestions")).isNotNull();
        }
        assertThat(questionNos).hasSize(20);
        assertThat(questionNos).allMatch(no -> no >= 1 && no <= 20);

        // 6. 数据库落库核验
        InterviewSession completed = sessionMapper.selectById(sessionId);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getFinishedAt()).isNotNull();

        InterviewReport report = reportMapper.selectOne(
                new QueryWrapper<InterviewReport>().eq("session_id", sessionId));
        assertThat(report).isNotNull();
        assertThat(report.getTotalScore()).isBetween(0, 100);
        assertThat(report.getSummary()).isNotBlank();

        List<InterviewAnswer> answers = answerMapper.selectList(
                new QueryWrapper<InterviewAnswer>());
        assertThat(answers).hasSize(20);
        assertThat(answers).allMatch(a -> a.getScore() != null
                && a.getScore() >= 0 && a.getScore() <= 10);
    }

    private String findRealResumeKey() {
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .build())) {
                String objectName = result.get().objectName();
                if (objectName.contains("简历")) {
                    return objectName;
                }
            }
        } catch (Exception e) {
            throw new AssertionError("列举 MinIO 对象失败", e);
        }
        throw new AssertionError("MinIO bucket 中未找到真实简历对象（名称需包含“简历”）");
    }

    private long uploadRealResume() throws Exception {
        String objectKey = findRealResumeKey();
        byte[] content;
        try (InputStream in = objectStorageService.download(objectKey)) {
            content = in.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", objectKey, MediaType.APPLICATION_PDF_VALUE, content);
        MvcResult result = mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.parse(body).read("$.data.resumeId", Long.class);
    }

}
