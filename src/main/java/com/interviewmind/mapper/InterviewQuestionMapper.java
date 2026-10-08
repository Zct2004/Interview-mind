package com.interviewmind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.interviewmind.entity.InterviewQuestion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewQuestionMapper extends BaseMapper<InterviewQuestion> {
}
