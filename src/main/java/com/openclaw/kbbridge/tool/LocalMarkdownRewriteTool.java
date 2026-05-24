package com.openclaw.kbbridge.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.kbbridge.config.DotenvPropertyLoader;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.processor.ProcessResult;
import com.openclaw.kbbridge.processor.QualityCheckResult;
import com.openclaw.kbbridge.processor.QualityChecker;
import com.openclaw.kbbridge.util.MarkdownUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地 Markdown 批量重写工具。
 * <p>
 * 这个入口只复用知识化重写所需的 prompt、LLM 调用和格式校验，不启动 Spring
 * Web 服务，也不访问数据库、MinIO、RAGFlow。
 * </p>
 */
public final class LocalMarkdownRewriteTool {

    static final Path DEFAULT_INPUT = Path.of("docs", "个人知识库");
    static final Path DEFAULT_OUTPUT = Path.of("docs", "个人知识库_rewrite");
    static final String DEFAULT_GUIDE_TEMPLATE = "classpath:prompts/guide-template.md";
    static final String DEFAULT_QA_TEMPLATE = "classpath:prompts/qa-template.md";
    static final String REPORT_FILE = "rewrite-report.jsonl";
    static final String SUBMITTED_FILE = "rewrite-submitted.jsonl";
    static final int DEFAULT_CONCURRENCY = 100;
    static final Duration DEFAULT_DOCUMENT_TIMEOUT = Duration.ofMinutes(10);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);

    private static final int MAX_IMAGE_REFERENCES_IN_PROMPT = 300;
    private static final int IMAGE_VALIDATION_ATTEMPTS = 2;
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[[^\\]\\n]*]\\([^\\)\\n]+\\)");
    private static final Pattern OBSIDIAN_IMAGE_PATTERN = Pattern.compile("!\\[\\[[^\\]\\n]+]]");
    private static final Pattern HTML_IMAGE_PATTERN = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LocalMarkdownRewriteTool() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        if (config.help()) {
            System.out.println(Config.usage());
            return;
        }

        LocalMarkdownRewriteTool tool = new LocalMarkdownRewriteTool();
        int exitCode = tool.run(config);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(Config config) throws Exception {
        Path input = config.input().toAbsolutePath().normalize();
        Path output = config.output().toAbsolutePath().normalize();

        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("输入目录不存在或不是目录: " + input);
        }

        List<Path> markdownFiles = findMarkdownFiles(input, output, config.limit());
        System.out.printf(Locale.ROOT, "待处理 Markdown: %d%n", markdownFiles.size());
        System.out.printf("输入目录: %s%n", input);
        System.out.printf("输出目录: %s%n", output);

        if (config.dryRun()) {
            long imageDirs = countImageDirs(input, output);
            long imageReferences = countImageReferences(markdownFiles);
            System.out.printf(Locale.ROOT, "Dry run: img 目录=%d, 图片引用=%d%n", imageDirs, imageReferences);
            return 0;
        }

        validateLlmConfig(config);
        Files.createDirectories(output);
        int copiedImageDirs = copyImageDirectories(input, output);
        System.out.printf(Locale.ROOT, "已复制 img 目录: %d%n", copiedImageDirs);

        String guidePrompt = loadPrompt(config.guidePromptTemplate());
        String qaPrompt = loadPrompt(config.qaPromptTemplate());
        OpenAiCompatibleClient llmClient = new OpenAiCompatibleClient(config);
        QualityChecker qualityChecker = new QualityChecker(config.toKbProperties());

        Path report = output.resolve(REPORT_FILE);
        Path submitted = output.resolve(SUBMITTED_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8);
                BufferedWriter submittedWriter = Files.newBufferedWriter(submitted, StandardCharsets.UTF_8)) {
            List<DocumentResult> results = processAll(
                    markdownFiles,
                    input,
                    output,
                    guidePrompt,
                    qaPrompt,
                    llmClient,
                    qualityChecker,
                    config,
                    writer,
                    submittedWriter);

            long completed = results.stream().filter(DocumentResult::completed).count();
            long skipped = results.stream().filter(result -> result.status() == Status.SKIPPED).count();
            long failed = results.stream().filter(result -> result.status() == Status.FAILED).count();
            long timedOut = results.stream().filter(result -> result.status() == Status.TIMEOUT).count();
            System.out.printf(Locale.ROOT, "完成: success=%d, skipped=%d, failed=%d, timeout=%d%n",
                    completed, skipped, failed, timedOut);
            System.out.printf("报告: %s%n", report);
            System.out.printf("已提交文档记录: %s%n", submitted);
            return failed + timedOut > 0 ? 2 : 0;
        }
    }

    private List<DocumentResult> processAll(
            List<Path> markdownFiles,
            Path input,
            Path output,
            String guidePrompt,
            String qaPrompt,
            OpenAiCompatibleClient llmClient,
            QualityChecker qualityChecker,
            Config config,
            BufferedWriter reportWriter,
            BufferedWriter submittedWriter) throws Exception {
        ArrayList<Path> pending = new ArrayList<>(markdownFiles);
        ArrayList<ActiveDocument> active = new ArrayList<>();
        ArrayList<DocumentResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());
        try {
            int nextIndex = 0;
            while (nextIndex < pending.size() && active.size() < config.concurrency()) {
                active.add(submitDocument(executor, pending.get(nextIndex++), input, output, guidePrompt, qaPrompt,
                        llmClient, qualityChecker, config, submittedWriter));
            }

            while (!active.isEmpty()) {
                boolean madeProgress = false;
                Instant now = Instant.now();
                for (int i = 0; i < active.size();) {
                    ActiveDocument document = active.get(i);
                    if (document.future().isDone()) {
                        DocumentResult result = collectResult(document);
                        writeReport(reportWriter, result);
                        printProgress(result);
                        results.add(result);
                        active.remove(i);
                        madeProgress = true;
                        continue;
                    }

                    if (Duration.between(document.submittedAt(), now).compareTo(config.documentTimeout()) > 0) {
                        document.future().cancel(true);
                        DocumentResult result = timeoutResult(document, config);
                        writeReport(reportWriter, result);
                        printProgress(result);
                        results.add(result);
                        active.remove(i);
                        madeProgress = true;
                        continue;
                    }
                    i++;
                }

                while (nextIndex < pending.size() && active.size() < config.concurrency()) {
                    active.add(submitDocument(executor, pending.get(nextIndex++), input, output, guidePrompt, qaPrompt,
                            llmClient, qualityChecker, config, submittedWriter));
                    madeProgress = true;
                }

                if (!madeProgress && !active.isEmpty()) {
                    Thread.sleep(config.pollInterval().toMillis());
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private ActiveDocument submitDocument(
            ExecutorService executor,
            Path markdownFile,
            Path input,
            Path output,
            String guidePrompt,
            String qaPrompt,
            OpenAiCompatibleClient llmClient,
            QualityChecker qualityChecker,
            Config config,
            BufferedWriter submittedWriter) throws IOException {
        Path relative = input.relativize(markdownFile);
        Path guideOutput = outputPath(output, relative, "guide");
        Path qaOutput = outputPath(output, relative, "qa");
        ImageSummary imageSummary = imageSummary(markdownFile);
        Instant submittedAt = Instant.now();
        Callable<DocumentResult> task = () -> processOne(markdownFile, input, output, guidePrompt, qaPrompt,
                llmClient, qualityChecker, config);
        Future<DocumentResult> future = executor.submit(task);
        ActiveDocument document = new ActiveDocument(
                markdownFile,
                guideOutput,
                qaOutput,
                imageSummary.imageReferenceCount(),
                imageSummary.missingSourceImages(),
                submittedAt,
                future);
        writeSubmitted(submittedWriter, new SubmittedDocument(
                markdownFile.toString(),
                guideOutput.toString(),
                qaOutput.toString(),
                imageSummary.imageReferenceCount(),
                imageSummary.missingSourceImages(),
                submittedAt.toString()));
        System.out.printf("[START] %s%n", markdownFile);
        return document;
    }

    private DocumentResult collectResult(ActiveDocument document) throws InterruptedException {
        try {
            return document.future().get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new DocumentResult(
                    Status.FAILED,
                    document.source().toString(),
                    document.guideOutput().toString(),
                    document.qaOutput().toString(),
                    document.imageReferenceCount(),
                    0,
                    document.missingSourceImages(),
                    List.of(cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage()),
                    elapsedMs(document.submittedAt()));
        } catch (java.util.concurrent.CancellationException e) {
            return timeoutResult(document, null);
        }
    }

    private DocumentResult timeoutResult(ActiveDocument document, Config config) {
        long timeoutMs = config == null ? DEFAULT_DOCUMENT_TIMEOUT.toMillis() : config.documentTimeout().toMillis();
        return new DocumentResult(
                Status.TIMEOUT,
                document.source().toString(),
                document.guideOutput().toString(),
                document.qaOutput().toString(),
                document.imageReferenceCount(),
                0,
                document.missingSourceImages(),
                List.of("文档处理超过 " + timeoutMs + "ms 未完成，已标记超时"),
                elapsedMs(document.submittedAt()));
    }

    private DocumentResult processOne(
            Path markdownFile,
            Path input,
            Path output,
            String guidePrompt,
            String qaPrompt,
            OpenAiCompatibleClient llmClient,
            QualityChecker qualityChecker,
            Config config) {
        Instant startedAt = Instant.now();
        Path relative = input.relativize(markdownFile);
        Path guideOutput = outputPath(output, relative, "guide");
        Path qaOutput = outputPath(output, relative, "qa");
        List<String> failures = new ArrayList<>();
        int imageReferenceCount = 0;
        List<String> missingSourceImages = List.of();
        int llmCalls = 0;

        try {
            String rawContent = Files.readString(markdownFile, StandardCharsets.UTF_8);
            ImageReferences imageReferences = extractImageReferences(rawContent);
            imageReferenceCount = imageReferences.totalCount();
            missingSourceImages = findMissingSourceImages(markdownFile, imageReferences);

            boolean guideExists = Files.exists(guideOutput);
            boolean qaExists = Files.exists(qaOutput);
            if (guideExists && qaExists && !config.overwrite()) {
                return new DocumentResult(
                        Status.SKIPPED,
                        markdownFile.toString(),
                        guideOutput.toString(),
                        qaOutput.toString(),
                        imageReferenceCount,
                        llmCalls,
                        missingSourceImages,
                        List.of(),
                        elapsedMs(startedAt));
            }

            Files.createDirectories(guideOutput.getParent());

            String guideContent;
            if (guideExists && !config.overwrite()) {
                guideContent = Files.readString(guideOutput, StandardCharsets.UTF_8);
            } else {
                RewriteOutput guide = rewriteWithImageValidation(
                        llmClient,
                        guidePrompt,
                        rawContent,
                        imageReferences,
                        "Guide",
                        config);
                guideContent = guide.content();
                llmCalls += guide.llmCalls();
                Files.writeString(guideOutput, guideContent, StandardCharsets.UTF_8);
            }

            String qaContent;
            if (qaExists && !config.overwrite()) {
                qaContent = Files.readString(qaOutput, StandardCharsets.UTF_8);
            } else {
                RewriteOutput qa = rewriteWithImageValidation(
                        llmClient,
                        qaPrompt,
                        rawContent,
                        imageReferences,
                        "Q&A",
                        config);
                qaContent = qa.content();
                llmCalls += qa.llmCalls();
                Files.writeString(qaOutput, qaContent, StandardCharsets.UTF_8);
            }

            QualityCheckResult quality = qualityChecker.check(
                    rawContent,
                    new ProcessResult(guideContent, qaContent, "local-batch"),
                    "MARKDOWN");
            if (!quality.passed()) {
                failures.addAll(quality.failures());
            }

            failures.addAll(prefixFailures("Guide 缺失图片引用", imageReferences.missingFrom(guideContent)));
            failures.addAll(prefixFailures("Q&A 缺失图片引用", imageReferences.missingFrom(qaContent)));
            failures.addAll(prefixFailures("源图片文件不存在", missingSourceImages));

            return new DocumentResult(
                    failures.isEmpty() ? Status.COMPLETED : Status.FAILED,
                    markdownFile.toString(),
                    guideOutput.toString(),
                    qaOutput.toString(),
                    imageReferenceCount,
                    llmCalls,
                    missingSourceImages,
                    failures,
                    elapsedMs(startedAt));
        } catch (Exception e) {
            failures.add(e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            return new DocumentResult(
                    Status.FAILED,
                    markdownFile.toString(),
                    guideOutput.toString(),
                    qaOutput.toString(),
                    imageReferenceCount,
                    llmCalls,
                    missingSourceImages,
                    failures,
                    elapsedMs(startedAt));
        }
    }

    private RewriteOutput rewriteWithImageValidation(
            OpenAiCompatibleClient llmClient,
            String promptTemplate,
            String rawContent,
            ImageReferences imageReferences,
            String outputKind,
            Config config) throws IOException, InterruptedException {
        List<String> lastMissing = List.of();
        int llmCalls = 0;
        for (int attempt = 1; attempt <= IMAGE_VALIDATION_ATTEMPTS; attempt++) {
            String systemPrompt = buildSystemPrompt(promptTemplate, imageReferences, outputKind, lastMissing);
            String userPrompt = "<content>\n" + rawContent + "\n</content>";
            String content = llmClient.complete(systemPrompt, userPrompt);
            llmCalls++;
            content = normalizeMarkdownOutput(content, config.keepFrontMatter());
            if ("Guide".equals(outputKind)) {
                content = ensureGuideChunkDelimiter(content);
            }
            lastMissing = imageReferences.missingFrom(content);
            if (lastMissing.isEmpty()) {
                return new RewriteOutput(content, llmCalls);
            }
        }
        throw new IllegalStateException(outputKind + " 重写缺失图片引用: " + String.join(", ", lastMissing));
    }

    static String ensureGuideChunkDelimiter(String content) {
        String safeContent = content == null ? "" : content.stripTrailing();
        if (safeContent.contains(MarkdownUtil.CHUNK_DELIMITER)) {
            return safeContent + "\n";
        }
        return safeContent + "\n\n" + MarkdownUtil.CHUNK_DELIMITER + "\n";
    }

    private static String buildSystemPrompt(
            String promptTemplate,
            ImageReferences imageReferences,
            String outputKind,
            List<String> lastMissing) {
        StringBuilder prompt = new StringBuilder(promptTemplate);
        if (imageReferences.isEmpty()) {
            return prompt.toString();
        }

        prompt.append("\n\n# 图片保留规则\n\n");
        prompt.append("原文中的 Markdown 图片、Obsidian 图片和 HTML img 标签都是文档资产引用。");
        prompt.append("重写 ").append(outputKind).append(" 时必须逐字保留这些图片引用，不得修改 alt、路径、括号、相对路径或标签属性。");
        prompt.append("每条图片引用至少在输出中出现与原文相同的次数，并放在与原文语义对应的位置。");
        prompt.append("如果无法判断语义位置，也必须在最相关的段落下方保留原始图片引用。\n\n");
        prompt.append("必须保留的图片引用：\n");

        int written = 0;
        for (Map.Entry<String, Integer> entry : imageReferences.references().entrySet()) {
            if (written >= MAX_IMAGE_REFERENCES_IN_PROMPT) {
                int remaining = imageReferences.references().size() - written;
                prompt.append("- 其余 ").append(remaining).append(" 条图片引用也必须按原文保留。\n");
                break;
            }
            prompt.append("- `").append(entry.getKey().replace("`", "\\`")).append("`");
            if (entry.getValue() > 1) {
                prompt.append("，出现次数：").append(entry.getValue());
            }
            prompt.append("\n");
            written++;
        }

        if (!lastMissing.isEmpty()) {
            prompt.append("\n上一轮输出缺失以下图片引用，本轮必须补齐：\n");
            for (String missing : lastMissing) {
                prompt.append("- `").append(missing.replace("`", "\\`")).append("`\n");
            }
        }
        return prompt.toString();
    }

    static List<Path> findMarkdownFiles(Path input, Path output, int limit) throws IOException {
        Path normalizedInput = input.toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.walk(normalizedInput)) {
            Stream<Path> markdownStream = stream
                    .filter(Files::isRegularFile)
                    .filter(LocalMarkdownRewriteTool::isMarkdown)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(normalizedOutput))
                    .sorted();
            if (limit > 0) {
                markdownStream = markdownStream.limit(limit);
            }
            return markdownStream.toList();
        }
    }

    static Path outputPath(Path outputRoot, Path relativeMarkdownPath, String suffix) {
        String fileName = relativeMarkdownPath.getFileName().toString();
        int extensionIndex = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".md");
        String baseName = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
        Path parent = relativeMarkdownPath.getParent();
        Path outputFile = Path.of(baseName + "_" + suffix + ".md");
        return parent == null ? outputRoot.resolve(outputFile) : outputRoot.resolve(parent).resolve(outputFile);
    }

    static ImageReferences extractImageReferences(String content) {
        if (content == null || content.isBlank()) {
            return new ImageReferences(Map.of());
        }

        Map<String, Integer> references = new LinkedHashMap<>();
        addMatches(content, MARKDOWN_IMAGE_PATTERN, references);
        addMatches(content, OBSIDIAN_IMAGE_PATTERN, references);
        addMatches(content, HTML_IMAGE_PATTERN, references);
        return new ImageReferences(references);
    }

    static int copyImageDirectories(Path input, Path output) throws IOException {
        Path normalizedInput = input.toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        int[] copied = {0};
        try (Stream<Path> stream = Files.walk(normalizedInput)) {
            List<Path> imageDirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> "img".equals(path.getFileName().toString()))
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(normalizedOutput))
                    .sorted()
                    .toList();
            for (Path imageDir : imageDirs) {
                Path targetDir = normalizedOutput.resolve(normalizedInput.relativize(imageDir));
                copyDirectory(imageDir, targetDir);
                copied[0]++;
            }
        }
        return copied[0];
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.createDirectories(targetFile.getParent());
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void addMatches(String content, Pattern pattern, Map<String, Integer> references) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String reference = matcher.group();
            references.merge(reference, 1, Integer::sum);
        }
    }

    private static List<String> findMissingSourceImages(Path markdownFile, ImageReferences references) {
        if (references.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String reference : references.references().keySet()) {
            String target = extractImageTarget(reference);
            if (target == null || shouldSkipFileExistenceCheck(target)) {
                continue;
            }
            Path resolved = markdownFile.getParent().resolve(target).normalize();
            if (!Files.exists(resolved)) {
                missing.add(reference + " -> " + resolved);
            }
        }
        return missing;
    }

    private static ImageSummary imageSummary(Path markdownFile) {
        try {
            ImageReferences references = extractImageReferences(Files.readString(markdownFile, StandardCharsets.UTF_8));
            return new ImageSummary(references.totalCount(), findMissingSourceImages(markdownFile, references));
        } catch (IOException e) {
            return new ImageSummary(0, List.of("读取源文件失败，无法统计图片引用: " + e.getMessage()));
        }
    }

    private static String extractImageTarget(String reference) {
        if (reference.startsWith("![[")) {
            return reference.substring(3, reference.length() - 2).trim();
        }
        if (reference.startsWith("<")) {
            Matcher matcher = Pattern.compile("\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)
                    .matcher(reference);
            return matcher.find() ? matcher.group(1).trim() : null;
        }
        int open = reference.indexOf('(');
        int close = reference.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        String inside = reference.substring(open + 1, close).trim();
        if (inside.startsWith("<") && inside.contains(">")) {
            return inside.substring(1, inside.indexOf('>')).trim();
        }
        int firstSpace = inside.indexOf(' ');
        return firstSpace > 0 ? inside.substring(0, firstSpace).trim() : inside;
    }

    private static boolean shouldSkipFileExistenceCheck(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.isBlank()
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("data:")
                || lower.startsWith("#");
    }

    private static String normalizeMarkdownOutput(String content, boolean keepFrontMatter) {
        String normalized = content == null ? "" : content.trim() + "\n";
        return keepFrontMatter ? normalized : MarkdownUtil.stripFrontMatter(normalized).trim() + "\n";
    }

    private static List<String> prefixFailures(String prefix, List<String> failures) {
        if (failures.isEmpty()) {
            return List.of();
        }
        List<String> prefixed = new ArrayList<>();
        for (String failure : failures) {
            prefixed.add(prefix + ": " + failure);
        }
        return prefixed;
    }

    private static void validateLlmConfig(Config config) {
        if (config.llmBaseUrl() == null || config.llmBaseUrl().isBlank()) {
            throw new IllegalArgumentException("缺少 LLM_BASE_URL 或 --llm-base-url");
        }
        if (config.llmApiKey() == null || config.llmApiKey().isBlank()) {
            throw new IllegalArgumentException("缺少 LLM_API_KEY 或 --llm-api-key");
        }
        if (config.llmModel() == null || config.llmModel().isBlank()) {
            throw new IllegalArgumentException("缺少 LLM_MODEL 或 --model");
        }
    }

    private static String loadPrompt(String templatePath) {
        String path = templatePath == null || templatePath.isBlank() ? "" : templatePath.trim();
        if (path.startsWith("classpath:")) {
            String resourcePath = path.substring("classpath:".length());
            try (InputStream inputStream = LocalMarkdownRewriteTool.class.getClassLoader()
                    .getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    throw new IllegalArgumentException("classpath prompt 不存在: " + templatePath);
                }
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取 prompt 失败: " + templatePath, e);
            }
        }

        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败: " + templatePath, e);
        }
    }

    private static boolean isMarkdown(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static long countImageDirs(Path input, Path output) throws IOException {
        Path normalizedOutput = output.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.walk(input.toAbsolutePath().normalize())) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> "img".equals(path.getFileName().toString()))
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(normalizedOutput))
                    .count();
        }
    }

    private static long countImageReferences(List<Path> markdownFiles) throws IOException {
        long total = 0;
        for (Path markdownFile : markdownFiles) {
            total += extractImageReferences(Files.readString(markdownFile, StandardCharsets.UTF_8)).totalCount();
        }
        return total;
    }

    private static void writeReport(BufferedWriter writer, DocumentResult result) throws IOException {
        writer.write(OBJECT_MAPPER.writeValueAsString(result));
        writer.newLine();
        writer.flush();
    }

    private static void writeSubmitted(BufferedWriter writer, SubmittedDocument submitted) throws IOException {
        writer.write(OBJECT_MAPPER.writeValueAsString(submitted));
        writer.newLine();
        writer.flush();
    }

    private static void printProgress(DocumentResult result) {
        String marker = switch (result.status()) {
            case COMPLETED -> "OK";
            case SKIPPED -> "SKIP";
            case FAILED -> "FAIL";
            case TIMEOUT -> "TIMEOUT";
        };
        System.out.printf(Locale.ROOT, "[%s] %s (%dms)%n", marker, result.source(), result.elapsedMs());
        if (!result.failures().isEmpty()) {
            for (String failure : result.failures()) {
                System.out.printf("  - %s%n", failure);
            }
        }
    }

    private static long elapsedMs(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    record Config(
            Path input,
            Path output,
            boolean overwrite,
            boolean dryRun,
            boolean keepFrontMatter,
            int concurrency,
            int limit,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            Duration timeout,
            Duration documentTimeout,
            Duration pollInterval,
            String guidePromptTemplate,
            String qaPromptTemplate,
            boolean help) {

        static Config fromArgs(String[] args) {
            Map<String, String> options = parseArgs(args);
            Map<String, Object> dotenv = DotenvPropertyLoader.load();

            boolean help = options.containsKey("help") || options.containsKey("h");
            Path input = Path.of(options.getOrDefault("input", DEFAULT_INPUT.toString()));
            Path output = Path.of(options.getOrDefault("output", DEFAULT_OUTPUT.toString()));
            boolean overwrite = flag(options, "overwrite");
            boolean dryRun = flag(options, "dry-run");
            boolean keepFrontMatter = flag(options, "keep-front-matter");
            int concurrency = Math.max(1, intOption(options, "concurrency", DEFAULT_CONCURRENCY));
            int limit = Math.max(0, intOption(options, "limit", 0));
            long timeoutMs = longOption(options, "timeout-ms",
                    Long.parseLong(resolve(dotenv, "LLM_TIMEOUT_MS", "30000")));
            long documentTimeoutMs = longOption(options, "document-timeout-ms",
                    Long.parseLong(resolve(dotenv, "LOCAL_REWRITE_DOCUMENT_TIMEOUT_MS",
                            String.valueOf(DEFAULT_DOCUMENT_TIMEOUT.toMillis()))));
            long pollIntervalMs = longOption(options, "poll-interval-ms",
                    Long.parseLong(resolve(dotenv, "LOCAL_REWRITE_POLL_INTERVAL_MS",
                            String.valueOf(DEFAULT_POLL_INTERVAL.toMillis()))));

            String baseUrl = valueOption(options, "llm-base-url", resolve(dotenv, "LLM_BASE_URL", ""));
            String apiKey = valueOption(options, "llm-api-key", resolve(dotenv, "LLM_API_KEY", ""));
            String model = valueOption(options, "model", resolve(dotenv, "LLM_MODEL", "gpt-4o"));
            String guidePrompt = valueOption(options, "guide-prompt",
                    resolve(dotenv, "PROCESSOR_GUIDE_PROMPT_TEMPLATE", DEFAULT_GUIDE_TEMPLATE));
            String qaPrompt = valueOption(options, "qa-prompt",
                    resolve(dotenv, "PROCESSOR_QA_PROMPT_TEMPLATE", DEFAULT_QA_TEMPLATE));

            return new Config(
                    input,
                    output,
                    overwrite,
                    dryRun,
                    keepFrontMatter,
                    concurrency,
                    limit,
                    baseUrl,
                    apiKey,
                    model,
                    Duration.ofMillis(timeoutMs),
                    Duration.ofMillis(Math.max(1000, documentTimeoutMs)),
                    Duration.ofMillis(Math.max(100, pollIntervalMs)),
                    guidePrompt,
                    qaPrompt,
                    help);
        }

        KbProperties toKbProperties() {
            KbProperties properties = new KbProperties();
            properties.getProcessor().setMinQaCount(3);
            return properties;
        }

        static String usage() {
            return """
                    本地 Markdown 批量重写工具

                    用法:
                      mvn -q -DskipTests compile exec:java "-Dexec.mainClass=com.openclaw.kbbridge.tool.LocalMarkdownRewriteTool" "-Dexec.args=--input docs/个人知识库 --output docs/个人知识库_rewrite"

                    参数:
                      --input <dir>              输入目录，默认 docs/个人知识库
                      --output <dir>             输出目录，默认 docs/个人知识库_rewrite
                      --overwrite                覆盖已有 *_guide.md / *_qa.md
                      --dry-run                  只扫描，不调用 LLM、不写文件
                      --keep-front-matter        保留 LLM 输出的 YAML front matter；默认按现有处理器逻辑去除
                      --concurrency <n>          同时处理的文档数，默认 100
                      --limit <n>                只处理前 n 篇，默认不限制
                      --llm-base-url <url>       OpenAI 兼容服务地址；默认读取 LLM_BASE_URL
                      --llm-api-key <key>        API Key；默认读取 LLM_API_KEY
                      --model <name>             模型；默认读取 LLM_MODEL 或 gpt-4o
                      --timeout-ms <ms>          请求超时；默认读取 LLM_TIMEOUT_MS 或 30000
                      --document-timeout-ms <ms> 单篇文档总处理超时，默认 600000
                      --poll-interval-ms <ms>    轮询活跃任务间隔，默认 2000
                      --guide-prompt <path>      Guide prompt；默认 classpath:prompts/guide-template.md
                      --qa-prompt <path>         Q&A prompt；默认 classpath:prompts/qa-template.md
                    """;
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> options = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("无法识别参数: " + arg);
                }
                String keyValue = arg.substring(2);
                int equals = keyValue.indexOf('=');
                if (equals >= 0) {
                    options.put(keyValue.substring(0, equals), keyValue.substring(equals + 1));
                    continue;
                }
                String key = keyValue;
                if (isBooleanFlag(key)) {
                    options.put(key, "true");
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("参数缺少值: " + arg);
                }
                options.put(key, args[++i]);
            }
            return options;
        }

        private static boolean isBooleanFlag(String key) {
            return Set.of("overwrite", "dry-run", "keep-front-matter", "help", "h").contains(key);
        }

        private static boolean flag(Map<String, String> options, String key) {
            return Boolean.parseBoolean(options.getOrDefault(key, "false"));
        }

        private static int intOption(Map<String, String> options, String key, int defaultValue) {
            String value = options.get(key);
            return value == null ? defaultValue : Integer.parseInt(value);
        }

        private static long longOption(Map<String, String> options, String key, long defaultValue) {
            String value = options.get(key);
            return value == null ? defaultValue : Long.parseLong(value);
        }

        private static String valueOption(Map<String, String> options, String key, String defaultValue) {
            return options.getOrDefault(key, defaultValue);
        }

        private static String resolve(Map<String, Object> dotenv, String key, String defaultValue) {
            String systemProperty = System.getProperty(key);
            if (systemProperty != null && !systemProperty.isBlank()) {
                return systemProperty;
            }
            String env = System.getenv(key);
            if (env != null && !env.isBlank()) {
                return env;
            }
            Object dotEnvValue = dotenv.get(key);
            if (dotEnvValue != null && !dotEnvValue.toString().isBlank()) {
                return dotEnvValue.toString();
            }
            return defaultValue;
        }
    }

    record ImageReferences(Map<String, Integer> references) {

        ImageReferences {
            references = Collections.unmodifiableMap(new LinkedHashMap<>(references));
        }

        boolean isEmpty() {
            return references.isEmpty();
        }

        int totalCount() {
            return references.values().stream().mapToInt(Integer::intValue).sum();
        }

        List<String> missingFrom(String content) {
            if (references.isEmpty()) {
                return List.of();
            }
            String safeContent = Objects.toString(content, "");
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : references.entrySet()) {
                int actual = countOccurrences(safeContent, entry.getKey());
                if (actual < entry.getValue()) {
                    missing.add(entry.getKey());
                }
            }
            return missing;
        }

        private static int countOccurrences(String content, String needle) {
            if (needle.isEmpty()) {
                return 0;
            }
            int count = 0;
            int index = 0;
            while ((index = content.indexOf(needle, index)) >= 0) {
                count++;
                index += needle.length();
            }
            return count;
        }
    }

    record RewriteOutput(String content, int llmCalls) {
    }

    record ActiveDocument(
            Path source,
            Path guideOutput,
            Path qaOutput,
            int imageReferenceCount,
            List<String> missingSourceImages,
            Instant submittedAt,
            Future<DocumentResult> future) {
    }

    record ImageSummary(int imageReferenceCount, List<String> missingSourceImages) {
    }

    record SubmittedDocument(
            String source,
            String guideOutput,
            String qaOutput,
            int imageReferenceCount,
            List<String> missingSourceImages,
            String submittedAt) {
    }

    record DocumentResult(
            Status status,
            String source,
            String guideOutput,
            String qaOutput,
            int imageReferenceCount,
            int llmCalls,
            List<String> missingSourceImages,
            List<String> failures,
            long elapsedMs) {

        boolean completed() {
            return status == Status.COMPLETED;
        }
    }

    enum Status {
        COMPLETED,
        SKIPPED,
        FAILED,
        TIMEOUT
    }

    static final class OpenAiCompatibleClient {
        private final HttpClient httpClient;
        private final URI endpoint;
        private final String apiKey;
        private final String model;
        private final Duration timeout;

        OpenAiCompatibleClient(Config config) {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(config.timeout())
                    .build();
            this.endpoint = completionEndpoint(config.llmBaseUrl());
            this.apiKey = config.llmApiKey();
            this.model = config.llmModel();
            this.timeout = config.timeout();
        }

        String complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));

            String requestJson = OBJECT_MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("LLM HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IOException("LLM 响应为空或不包含 choices[0].message.content");
            }
            return content.asText();
        }

        private static URI completionEndpoint(String baseUrl) {
            String trimmed = baseUrl.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            String suffix = trimmed.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
            return URI.create(trimmed + suffix);
        }
    }
}
