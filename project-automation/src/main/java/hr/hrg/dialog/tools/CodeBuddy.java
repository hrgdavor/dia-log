package hr.hrg.dialog.tools;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * CodeBuddy: marker-driven source generator for the dia-log project.
 * <p>
 * Scans the repository for {@code // @CB.<instruction> ...} marker comments and
 * applies the matching instruction to the lines below each marker. The marker
 * line is the source of truth; the tool rewrites (never hand-edits) the
 * generated block beneath it, so regeneration is safe and idempotent.
 * <p>
 * Instructions:
 * <ul>
 *   <li>{@code @CB.StrPacker} — replaces a runtime-initialized static field block
 *       with gen-time computed compile-time constants (see {@link StrPacker})</li>
 * </ul>
 * <p>
 * Usage from the repository root:
 * <pre>{@code
 *   mvn -pl project-automation compile exec:java \
 *       -Dexec.mainClass=hr.hrg.dialog.tools.CodeBuddy
 * }</pre>
 * A repository root argument may be passed as the first program argument.
 */
public final class CodeBuddy {

    private static final Path DEFAULT_REPO_ROOT = Paths.get("").toAbsolutePath();

    private static final List<String> SKIPPED_DIRS =
            List.of("target", "node_modules", ".git", ".idea", ".vscode", ".kilo");

    private final Path repoRoot;
    // Sources use JDK 25 features (records, switch patterns); the parser must be
    // configured with the matching language level or it rejects them.
    private final JavaParser javaParser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));

    public CodeBuddy(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public CodeBuddy() {
        this(DEFAULT_REPO_ROOT);
    }

    public static void main(String[] args) throws IOException {
        Path root = args.length >= 1 ? Paths.get(args[0]).toAbsolutePath() : DEFAULT_REPO_ROOT;
        new CodeBuddy(root).run();
    }

    /** Applies every recognized {@code @CB.*} instruction to the sources under the repo root. */
    public void run() throws IOException {
        int processed = 0;
        int changed = 0;
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(CodeBuddy::notInSkippedDir)
                    .sorted()
                    .toList();
            for (Path file : files) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("@CB.")) {
                    continue;
                }
                processed++;
                String rewritten = applyInstructions(source, file);
                if (rewritten.equals(source)) {
                    continue;
                }
                validateParses(file, rewritten);
                Files.writeString(file, rewritten, StandardCharsets.UTF_8);
                changed++;
                System.out.println("  updated " + file);
            }
        }
        System.out.println("CodeBuddy: " + processed + " file(s) with @CB.* markers, " + changed + " updated");
    }

    /** Dispatches a source text to the instruction processors for the markers it contains. */
    private static String applyInstructions(String source, Path file) {
        if (source.contains(StrPacker.MARKER)) {
            return StrPacker.processFileText(source);
        }
        // Known marker token without a registered instruction: leave untouched.
        System.out.println("  (no processor for markers in " + file + ")");
        return source;
    }

    private static boolean notInSkippedDir(Path p) {
        for (Path part : p) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private void validateParses(Path file, String code) {
        ParseResult<CompilationUnit> result = javaParser.parse(code);
        if (!result.isSuccessful()) {
            throw new IllegalStateException("Rewritten source failed to parse for " + file
                    + " -> " + result.getProblems());
        }
    }
}
