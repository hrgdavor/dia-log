package hr.hrg.dialog.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the project-automation fat jar. Dispatches to the named tool.
 * <pre>{@code
 *   java -jar dia-log-project-automation.jar derivative [repo-root]
 *   java -jar dia-log-project-automation.jar codebuddy [repo-root]
 * }</pre>
 */
public final class Tools {

    private Tools() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        String tool = args[0];
        String[] toolArgs = new String[args.length - 1];
        System.arraycopy(args, 1, toolArgs, 0, toolArgs.length);
        Path root = toolArgs.length >= 1 ? Paths.get(toolArgs[0]).toAbsolutePath() : null;
        switch (tool) {
            case "derivative" -> {
                if (root != null) {
                    new StackSanitizerDerivativeGenerator(root).generateAll();
                } else {
                    new StackSanitizerDerivativeGenerator().generateAll();
                }
            }
            case "codebuddy" -> {
                if (root != null) {
                    new CodeBuddy(root).run();
                } else {
                    new CodeBuddy().run();
                }
            }
            case "all" -> {
                if (root != null) {
                    new StackSanitizerDerivativeGenerator(root).generateAll();
                    new CodeBuddy(root).run();
                } else {
                    new StackSanitizerDerivativeGenerator().generateAll();
                    new CodeBuddy().run();
                }
            }
            default -> {
                System.err.println("Unknown tool: " + tool);
                usage();
                System.exit(1);
            }
        }
    }

    private static void usage() {
        System.err.println("Usage: java -jar dia-log-project-automation.jar <tool> [repo-root]");
        System.err.println();
        System.err.println("Tools:");
        System.err.println("  derivative   Regenerate the 3 JavaStackSanitizer derivatives");
        System.err.println("  codebuddy    Apply @CB.* marker instructions to sources");
        System.err.println("  all          Run derivative, then codebuddy");
    }
}
