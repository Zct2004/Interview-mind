package com.interviewmind.rag;

/**
 * 单个知识点的历史表现与动态出题权重（只读 DTO）。
 * <p>
 * weight 完全由后端规则计算（{@link #computeWeight}），绝不交给 LLM 计算；
 * 同一 knowledgePoint 只产生一条记录，权重只算一次，不会因检索回多条记忆而重复放大。
 * <p>
 * 第一版权重规则（固定）：
 * <pre>
 * averageScore [0,4)   → baseWeight 2.0
 * averageScore [4,6)   → baseWeight 1.5
 * averageScore [6,8)   → baseWeight 1.0
 * averageScore [8,10]  → baseWeight 0.6
 * weight = baseWeight + weakPointCount × 0.2，最终钳制到 [0.5, 3.0]
 * </pre>
 *
 * @param knowledgePoint 知识点（非空白）
 * @param averageScore   该知识点历史平均分（已钳制到 0-10）
 * @param weakPointCount 其中 WEAK_POINT 类型的记忆条数
 * @param memoryCount    参与聚合的记忆总条数
 * @param weight         动态出题权重（0.5 ~ 3.0）
 */
public record InterviewQuestionWeight(
        String knowledgePoint,
        double averageScore,
        int weakPointCount,
        int memoryCount,
        double weight) {

    /** weight 下限 */
    public static final double MIN_WEIGHT = 0.5;

    /** weight 上限（防止历史薄弱点被无限放大） */
    public static final double MAX_WEIGHT = 3.0;

    /**
     * 无有效分数时的中性分：落在 [6,8) → baseWeight=1.0，
     * 与“没有历史时 weight=1.0”的语义对齐。
     */
    public static final double NEUTRAL_SCORE = 7.0;

    /**
     * 由聚合统计构建权重记录：分数越低权重越高，薄弱点数量线性加成，两端钳制。
     * NaN / Infinity 等异常输入一律回退为中性分与中性权重，绝不外泄非有限值。
     */
    public static InterviewQuestionWeight of(String knowledgePoint, double averageScore,
                                             int weakPointCount, int memoryCount) {
        double safeScore = Double.isFinite(averageScore)
                ? Math.max(0, Math.min(10, averageScore))
                : NEUTRAL_SCORE;
        double weight = computeWeight(baseWeight(safeScore), weakPointCount);
        return new InterviewQuestionWeight(
                knowledgePoint,
                safeScore,
                Math.max(0, weakPointCount),
                Math.max(0, memoryCount),
                weight);
    }

    /**
     * 分数段 → baseWeight。区间右开，边界无歧义：
     * [0,4)→2.0、[4,6)→1.5、[6,8)→1.0、[8,10]→0.6。
     */
    public static double baseWeight(double averageScore) {
        if (averageScore < 4) {
            return 2.0;
        }
        if (averageScore < 6) {
            return 1.5;
        }
        if (averageScore < 8) {
            return 1.0;
        }
        return 0.6;
    }

    /**
     * weight = baseWeight + weakPointCount × 0.2，钳制 [0.5, 3.0]；
     * 非有限结果（NaN/Infinity）防御性回退 1.0。
     */
    public static double computeWeight(double baseWeight, int weakPointCount) {
        double weight = baseWeight + Math.max(0, weakPointCount) * 0.2;
        if (!Double.isFinite(weight)) {
            return 1.0;
        }
        return Math.min(MAX_WEIGHT, Math.max(MIN_WEIGHT, weight));
    }

}
