package com.interviewmind.parser;

import com.interviewmind.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache PDFBox 3.0.8 实现。只负责 PDF → String，不做任何简历字段识别。
 */
@Slf4j
@Component
public class PdfBoxParser implements PdfParser {

    @Override
    public String parse(InputStream inputStream) {
        String extracted;
        // RandomAccessReadBuffer 把流缓冲为分块内存结构（PDF 随机访问需要），
        // PDDocument 关闭时二者都会释放；调用方的 InputStream 由调用方关闭。
        try (RandomAccessRead source = new RandomAccessReadBuffer(inputStream);
             PDDocument document = Loader.loadPDF(source)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            extracted = stripper.getText(document);
        } catch (IOException e) {
            log.error("PDFBox 解析 PDF 失败", e);
            throw new BusinessException(422, "PDF 解析失败");
        }

        String cleaned = clean(extracted);
        if (cleaned.isEmpty()) {
            throw new BusinessException(422, "PDF 未提取到任何文本内容");
        }
        return cleaned;
    }

    /**
     * 基础清洗：统一换行符 → 去行首尾空白 → 连续空行压缩为一行 → 去首尾空行。
     * 只处理空白，不修改文本内容本身。
     */
    private String clean(String text) {
        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\f', '\n'); // 页分隔符视作换行

        List<String> cleanedLines = new ArrayList<>();
        boolean previousBlank = true;
        for (String line : normalized.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (!previousBlank) {
                    cleanedLines.add("");
                    previousBlank = true;
                }
            } else {
                cleanedLines.add(stripped);
                previousBlank = false;
            }
        }
        while (!cleanedLines.isEmpty() && cleanedLines.get(cleanedLines.size() - 1).isEmpty()) {
            cleanedLines.remove(cleanedLines.size() - 1);
        }
        return String.join("\n", cleanedLines);
    }

}
