package com.interviewmind;

import com.interviewmind.rag.InterviewQuestionWeight;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 动态权重规则单元测试：分数段 → baseWeight、薄弱点加成、上下限钳制、
 * 以及 NaN / Infinity / 越界分数的防御。
 */
class InterviewQuestionWeightTest {

    // ---------- 分数段边界（weakPointCount = 0，weight == baseWeight） ----------

    @Test
    void baseWeightByScoreSegments() {
        assertThat(InterviewQuestionWeight.baseWeight(0)).isEqualTo(2.0);
        assertThat(InterviewQuestionWeight.baseWeight(3.99)).isEqualTo(2.0);
        // 边界右开：[0,4)→2.0、[4,6)→1.5、[6,8)→1.0、[8,10]→0.6
        assertThat(InterviewQuestionWeight.baseWeight(4)).isEqualTo(1.5);
        assertThat(InterviewQuestionWeight.baseWeight(5)).isEqualTo(1.5);
        assertThat(InterviewQuestionWeight.baseWeight(6)).isEqualTo(1.0);
        assertThat(InterviewQuestionWeight.baseWeight(7)).isEqualTo(1.0);
        assertThat(InterviewQuestionWeight.baseWeight(8)).isEqualTo(0.6);
        assertThat(InterviewQuestionWeight.baseWeight(9)).isEqualTo(0.6);
        assertThat(InterviewQuestionWeight.baseWeight(10)).isEqualTo(0.6);
    }

    @Test
    void weightWithoutWeakPointsEqualsBaseWeight() {
        for (int score = 0; score <= 10; score++) {
            InterviewQuestionWeight weight =
                    InterviewQuestionWeight.of("知识点", score, 0, 3);
            assertThat(weight.weight())
                    .isEqualTo(InterviewQuestionWeight.baseWeight(score), within(0.0001));
            assertThat(weight.averageScore()).isEqualTo(score);
        }
    }

    // ---------- weakPointCount 加成 ----------

    @Test
    void weakPointCountAddsLinearBonus() {
        InterviewQuestionWeight weight = InterviewQuestionWeight.of("Redis分布式锁", 4.5, 3, 5);
        assertThat(weight.averageScore()).isEqualTo(4.5);
        assertThat(weight.weakPointCount()).isEqualTo(3);
        assertThat(weight.memoryCount()).isEqualTo(5);
        // base 1.5 + 3 × 0.2 = 2.1
        assertThat(weight.weight()).isCloseTo(2.1, within(0.0001));
    }

    // ---------- 上下限钳制 ----------

    @Test
    void weightClampedToMax3() {
        // base 2.0（score 0）+ 20 × 0.2 = 6.0 → 钳制 3.0
        InterviewQuestionWeight weight = InterviewQuestionWeight.of("极弱知识点", 0, 20, 20);
        assertThat(weight.weight()).isEqualTo(InterviewQuestionWeight.MAX_WEIGHT);
        // 自然状态下的最大值：2.0 + N×0.2 首次触顶在 weak=5
        assertThat(InterviewQuestionWeight.computeWeight(2.0, 5)).isEqualTo(3.0);
    }

    @Test
    void weightClampedToMin05() {
        assertThat(InterviewQuestionWeight.computeWeight(0.1, 0))
                .isEqualTo(InterviewQuestionWeight.MIN_WEIGHT);
        // 正常规则下最小值 = 0.6（score≥8 且无薄弱点），永远 ≥ 0.5
        InterviewQuestionWeight weight = InterviewQuestionWeight.of("高分知识点", 10, 0, 4);
        assertThat(weight.weight()).isGreaterThanOrEqualTo(InterviewQuestionWeight.MIN_WEIGHT);
        assertThat(weight.weight()).isEqualTo(0.6, within(0.0001));
    }

    // ---------- 异常输入防御：NaN / Infinity / 越界分数 ----------

    @Test
    void nonFiniteScoreFallsBackToNeutral() {
        InterviewQuestionWeight nan = InterviewQuestionWeight.of("知识点A", Double.NaN, 1, 2);
        assertThat(Double.isFinite(nan.averageScore())).isTrue();
        assertThat(Double.isFinite(nan.weight())).isTrue();
        assertThat(nan.averageScore())
                .isEqualTo(InterviewQuestionWeight.NEUTRAL_SCORE, within(0.0001));

        InterviewQuestionWeight infinite = InterviewQuestionWeight.of("知识点A",
                Double.POSITIVE_INFINITY, 0, 1);
        assertThat(Double.isFinite(infinite.weight())).isTrue();
    }

    @Test
    void computeWeightNeverReturnsNonFinite() {
        assertThat(Double.isFinite(InterviewQuestionWeight.computeWeight(Double.NaN, 3))).isTrue();
        assertThat(Double.isFinite(
                InterviewQuestionWeight.computeWeight(Double.NEGATIVE_INFINITY, 0))).isTrue();
        assertThat(InterviewQuestionWeight.computeWeight(Double.NaN, 3))
                .isEqualTo(1.0, within(0.0001));
    }

    @Test
    void outOfRangeScoreClampedTo010() {
        assertThat(InterviewQuestionWeight.of("知识点", 15, 0, 1).averageScore()).isEqualTo(10);
        assertThat(InterviewQuestionWeight.of("知识点", -3, 0, 1).averageScore()).isEqualTo(0);
        // 负薄弱点数按 0 处理
        assertThat(InterviewQuestionWeight.of("知识点", 5, -2, 1).weakPointCount()).isEqualTo(0);
    }

}
