import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parses the Java source tree without resolving third-party dependencies. */
public final class JavaSyntaxGate {
    private JavaSyntaxGate() {
    }

    public static void main(String[] args) throws IOException {
        Path sourceRoot = Path.of("src");
        List<File> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .map(Path::toFile)
                    .toList();
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("No Java source files were found.");
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK 21 is required.");
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var files = manager.getJavaFileObjectsFromFiles(sources);
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:none"), null, files);
            task.parse();
        }
        var errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .toList();
        errors.forEach(System.err::println);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.size() + " Java syntax error(s) detected.");
        }
        System.out.println("Parsed " + sources.size() + " Java files without syntax errors."
                + " Dependency resolution and compilation still require Maven.");
    }
}
