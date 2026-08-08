package com.openclaw.kbbridge.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class FileTextExtractor {
    public String extract(String fileName, byte[] content) {
        String extension = extension(fileName);
        try {
            String text = switch (extension) {
                case "txt", "md", "csv", "json" -> decodeUtf8(content);
                case "pdf" -> extractPdf(content);
                case "docx" -> extractDocx(content);
                default -> throw new IllegalArgumentException("不支持的文件格式: " + extension);
            };
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("文件未抽取到有效文本");
            }
            return normalized;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文件文本抽取失败", e);
        }
    }

    static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String decodeUtf8(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content)).toString();
    }

    private String extractDocx(byte[] content) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractPdf(byte[] content) throws Exception {
        try (var document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
