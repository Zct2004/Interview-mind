package com.interviewmind.parser;

import java.io.InputStream;

/**
 * PDF 文本提取：InputStream → 纯文本。
 * <p>
 * 只负责 PDF 解析与基础文本清洗，不依赖数据库、对象存储或任何业务组件。
 */
public interface PdfParser {

    /**
     * 解析 PDF 并返回清洗后的纯文本。
     *
     * @param inputStream PDF 输入流，由调用方负责关闭
     * @return 清洗后的纯文本，至少包含一个非空白字符
     * @throws com.interviewmind.exception.BusinessException PDF 损坏无法解析，或解析结果为空
     */
    String parse(InputStream inputStream);

}
