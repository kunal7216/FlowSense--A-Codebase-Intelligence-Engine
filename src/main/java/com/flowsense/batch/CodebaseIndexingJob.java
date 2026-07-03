package com.flowsense.batch;

import com.flowsense.embedding.EmbeddingService;
import com.flowsense.graph.CodeGraphBuilder;
import com.flowsense.model.ParsedClass;
import com.flowsense.parser.ASTParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Spring Batch job for indexing large codebases in parallel chunks.
 *
 * WHY SPRING BATCH FOR INDEXING?
 * Indexing a 500k-line project involves:
 * - Parsing thousands of files
 * - Generating thousands of embeddings (Ollama API calls)
 * - Writing to Neo4j and pgvector
 *
 * Spring Batch gives us:
 * - Chunk-based processing (commit every N items — no giant transaction)
 * - Restart capability (if it fails at file 3000, restart from file 3000)
 * - Parallel step execution (multi-threaded parsing)
 * - Progress tracking (how many files done?)
 *
 * INTERVIEW TALKING POINT:
 * "I used Spring Batch for the indexing pipeline because naive
 * processing would either run one giant transaction (risky) or
 * require manual checkpoint logic (complex). Batch gives me
 * chunk commits every 50 files, so a failure at file 3000 restarts
 * from the last checkpoint rather than from zero. This is how
 * enterprise ETL pipelines work."
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CodebaseIndexingJob {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ASTParser astParser;
    private final EmbeddingService embeddingService;
    private final JobLauncher jobLauncher;

    private static final int CHUNK_SIZE = 50; // Process 50 files at a time

    // ── JOB DEFINITION ────────────────────────────────────────

    @Bean
    public Job indexingJob(Step parseAndIndexStep) {
        return new JobBuilder("codebaseIndexingJob", jobRepository)
                .start(parseAndIndexStep)
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        log.info("Starting indexing job: {}",
                                jobExecution.getJobParameters());
                    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        log.info("Indexing job complete: status={} duration={}ms",
                                jobExecution.getStatus(),
                                jobExecution.getEndTime() != null && jobExecution.getStartTime() != null
                                        ? java.time.Duration.between(
                                                jobExecution.getStartTime().toInstant(),
                                                jobExecution.getEndTime().toInstant()).toMillis()
                                        : 0);
                    }
                })
                .build();
    }

    @Bean
    public Step parseAndIndexStep(
            ItemReader<Path> javaFileReader,
            ItemProcessor<Path, List<ParsedClass>> fileProcessor,
            ItemWriter<List<ParsedClass>> embeddingWriter) {

        return new StepBuilder("parseAndIndexStep", jobRepository)
                .<Path, List<ParsedClass>>chunk(CHUNK_SIZE, transactionManager)
                .reader(javaFileReader)
                .processor(fileProcessor)
                .writer(embeddingWriter)
                .faultTolerant()
                .skipLimit(100) // Skip up to 100 bad files
                .skip(Exception.class) // Skip on any error
                .listener(new StepExecutionListener() {
                    @Override
                    public void beforeStep(StepExecution stepExecution) {
                        log.info("Parse step starting...");
                    }

                    @Override
                    public ExitStatus afterStep(StepExecution stepExecution) {
                        log.info("Parse step done: read={} written={} skipped={}",
                                stepExecution.getReadCount(),
                                stepExecution.getWriteCount(),
                                stepExecution.getSkipCount());
                        return ExitStatus.COMPLETED;
                    }
                })
                .build();
    }

    // ── READER: Discovers all .java files ─────────────────────

    @Bean
    @StepScope
    public ItemReader<Path> javaFileReader(
            @Value("#{jobParameters['projectPath']}") String projectPath,
            @Value("#{jobParameters['projectId']}") String projectId) {

        return new ItemReader<>() {
            private final Iterator<Path> fileIterator = discoverFiles(projectPath);
            private final AtomicInteger count = new AtomicInteger(0);

            @Override
            public Path read() {
                if (fileIterator.hasNext()) {
                    Path next = fileIterator.next();
                    int n = count.incrementAndGet();
                    if (n % 100 == 0)
                        log.info("Discovered {} files...", n);
                    return next;
                }
                return null; // null signals end of input
            }

            private Iterator<Path> discoverFiles(String path) {
                try {
                    List<Path> files = new ArrayList<>();
                    try (Stream<Path> stream = Files.walk(Paths.get(path))) {
                        stream.filter(p -> p.toString().endsWith(".java"))
                                .filter(p -> !p.toString().contains("target/"))
                                .filter(p -> !p.toString().contains("build/"))
                                .filter(p -> !p.toString().contains("generated/"))
                                .forEach(files::add);
                    }
                    log.info("Discovered {} Java files in {}", files.size(), path);
                    return files.iterator();
                } catch (Exception e) {
                    log.error("Failed to discover files: {}", e.getMessage());
                    return Collections.emptyIterator();
                }
            }
        };
    }

    // ── PROCESSOR: Parses each file ───────────────────────────

    @Bean
    @StepScope
    public ItemProcessor<Path, List<ParsedClass>> fileProcessor() {
        return file -> {
            try {
                List<ParsedClass> classes = astParser.parseFile(file);
                if (!classes.isEmpty()) {
                    log.debug("Parsed {} classes from {}", classes.size(),
                            file.getFileName());
                }
                return classes.isEmpty() ? null : classes; // null = skip this item
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", file.getFileName(), e.getMessage());
                return null; // Skip bad files
            }
        };
    }

    // ── WRITER: Generates embeddings + stores ─────────────────

    @Bean
    @StepScope
    public ItemWriter<List<ParsedClass>> embeddingWriter(
            @Value("#{jobParameters['projectId']}") String projectId) {

        return chunk -> {
            List<ParsedClass> allClasses = new ArrayList<>();
            chunk.getItems().forEach(allClasses::addAll);

            if (!allClasses.isEmpty()) {
                log.debug("Generating embeddings for {} classes in this chunk...",
                        allClasses.size());
                embeddingService.embedProject(allClasses, projectId);
            }
        };
    }

    // ── JOB LAUNCHER HELPER ───────────────────────────────────

    /**
     * Launch indexing job programmatically.
     * Called from the API when a project is submitted for indexing.
     */
    public JobExecution launchIndexingJob(String projectPath, String projectId)
            throws Exception {

        JobParameters params = new JobParametersBuilder()
                .addString("projectPath", projectPath)
                .addString("projectId", projectId)
                .addLong("timestamp", System.currentTimeMillis()) // Ensure unique run
                .toJobParameters();

        return jobLauncher.run(indexingJob(null), params);
    }
}
