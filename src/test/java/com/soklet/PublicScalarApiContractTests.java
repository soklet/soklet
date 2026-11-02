package com.soklet;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Reference scalars are policy; Java annotation and overridden platform contracts are exceptions. */
class PublicScalarApiContractTests {
  @Test
  void exportedScalarSignaturesUseReferences() throws Exception {
    List<Path> sources;
    try (var files = Files.walk(Path.of("src/main/java"))) {
      sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
    assertEquals(List.of(), violations(sources),
        "Exported scalar fields, constructors and methods must use non-primitive types");
  }

  @Test
  void histogramReferenceInputRejectsNullAndRecordsValues() {
    MetricsCollector.Histogram histogram = new MetricsCollector.Histogram(new long[]{5L});
    assertThrows(NullPointerException.class, () -> histogram.record((Long) null));
    histogram.record(Long.valueOf(4L));
    assertEquals(Long.valueOf(1L), histogram.snapshot().getCount());
    assertEquals(Double.valueOf(4D), histogram.snapshot().getSum());
  }

  @Test
  void guardCoversImplicitProtectedInheritedAndConstructorSignatures(@TempDir Path directory) throws Exception {
    Path source = directory.resolve("ScalarPolicyProbe.java");
    Files.writeString(source, """
        package com.soklet.contractprobe;
        public class ScalarPolicyProbe extends java.io.InputStream implements Comparable<ScalarPolicyProbe> {
          public static final int BAD = 1;
          public ScalarPolicyProbe(long bad) {}
          public boolean ownFlag() { return true; }
          protected void ownOffset(int bad) {}
          public byte[] bytes() { return new byte[0]; }
          public int read() { return -1; }
          public int compareTo(ScalarPolicyProbe other) { return 0; }
          public boolean equals(Object other) { return this == other; }
          public int hashCode() { return 0; }
          public @interface Configuration { boolean enabled(); }
          public interface Factory { long value(); }
          protected interface ProtectedFactory { double value(); }
          public static class ExposesInherited extends Hidden {}
        }
        class Hidden { public int inheritedValue() { return 1; } }
        """, StandardCharsets.UTF_8);
    List<String> failures = violations(List.of(source));
    assertEquals(7, failures.size(), failures.toString());
    for (String member : List.of("BAD", "<init>", "ownFlag", "ownOffset", "Factory#value", "ProtectedFactory#value", "inheritedValue"))
      assertTrue(failures.stream().anyMatch(failure -> failure.contains(member)), member + ": " + failures);
  }

  private static List<String> violations(List<Path> sources) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "A full JDK is required to inspect exported source signatures");
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
      JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
          List.of("--release", "21", "-proc:none", "-classpath", System.getProperty("java.class.path")),
          null, files.getJavaFileObjectsFromPaths(sources));
      List<CompilationUnitTree> units = new ArrayList<>();
      task.parse().forEach(units::add);
      task.analyze();
      List<String> errors = diagnostics.getDiagnostics().stream()
          .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).map(Object::toString).toList();
      assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
      Elements elements = task.getElements();
      Types types = task.getTypes();
      Trees trees = Trees.instance(task);
      List<TypeElement> exported = new ArrayList<>();
      for (CompilationUnitTree unit : units) {
        if (unit.getPackageName() == null || !unit.getPackageName().toString().startsWith("com.soklet")
            || unit.getPackageName().toString().contains(".internal")) continue;
        for (var declaration : unit.getTypeDecls()) {
          Element element = trees.getElement(TreePath.getPath(unit, declaration));
          if (element instanceof TypeElement type && type.getModifiers().contains(Modifier.PUBLIC)) append(type, exported);
        }
      }
      List<String> failures = new ArrayList<>();
      for (TypeElement type : exported) {
        for (Element member : elements.getAllMembers(type)) {
          if (!visible(member) || externalOwner(member)) continue;
          String owner = elements.getBinaryName(type) + "#" + member.getSimpleName();
          if (member.getKind() == ElementKind.FIELD && member.asType().getKind().isPrimitive())
            failures.add(owner + " field: " + member.asType());
          if (!(member instanceof ExecutableElement method)) continue;
          // Annotation elements cannot use boxed primitives under JLS 9.6.1.
          // Override signatures are fixed by Object, Comparable, I/O and Servlet APIs.
          if (type.getKind() == ElementKind.ANNOTATION_TYPE || overridesPlatform(type, type, method, elements, types, new HashSet<>())) continue;
          if (method.getReturnType().getKind().isPrimitive()) failures.add(owner + " return: " + method.getReturnType());
          for (var parameter : method.getParameters())
            if (parameter.asType().getKind().isPrimitive()) failures.add(owner + " parameter " + parameter.getSimpleName() + ": " + parameter.asType());
        }
      }
      return failures.stream().sorted().toList();
    }
  }

  private static boolean overridesPlatform(TypeElement context, TypeElement owner, ExecutableElement method,
      Elements elements, Types types, Set<String> visited) {
    if (method.getKind() != ElementKind.METHOD || method.getModifiers().contains(Modifier.STATIC)) return false;
    for (TypeMirror supertype : types.directSupertypes(owner.asType())) {
      TypeElement parent = (TypeElement) types.asElement(supertype);
      if (!visited.add(parent.getQualifiedName().toString())) continue;
      if (externalOwner(parent))
        for (Element member : parent.getEnclosedElements())
          if (member instanceof ExecutableElement inherited && elements.overrides(method, inherited, context)) return true;
      if (overridesPlatform(context, parent, method, elements, types, visited)) return true;
    }
    return false;
  }

  private static boolean externalOwner(Element element) {
    Element owner = element instanceof TypeElement ? element : element.getEnclosingElement();
    return owner instanceof TypeElement type && !type.getQualifiedName().toString().startsWith("com.soklet.");
  }

  private static boolean visible(Element element) {
    return element.getModifiers().contains(Modifier.PUBLIC) || element.getModifiers().contains(Modifier.PROTECTED);
  }

  private static void append(TypeElement type, List<TypeElement> result) {
    result.add(type);
    for (Element member : type.getEnclosedElements()) if (member instanceof TypeElement nested && visible(nested)) append(nested, result);
  }
}
