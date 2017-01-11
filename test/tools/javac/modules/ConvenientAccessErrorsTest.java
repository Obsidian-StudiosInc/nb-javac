/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Check convenient errors are produced for inaccessible classes.
 * @library /tools/lib
 * @modules jdk.compiler/com.sun.tools.javac.api
 *          jdk.compiler/com.sun.tools.javac.main
 * @build toolbox.ToolBox toolbox.JarTask toolbox.JavacTask ModuleTestBase
 * @run main ConvenientAccessErrorsTest
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import toolbox.JarTask;
import toolbox.JavacTask;
import toolbox.Task;

public class ConvenientAccessErrorsTest extends ModuleTestBase {

    public static void main(String... args) throws Exception {
        new ConvenientAccessErrorsTest().runTests();
    }

    @Test
    public void testNoDep(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { exports api; }",
                          "package api; public class Api { public void call() { } }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { }",
                          "package test; public class Test { api.Api api; }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:35: compiler.err.package.not.visible: api, (compiler.misc.not.def.access.does.not.read: m2x, api, m1x)",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testNotExported(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { exports api; }",
                          "package api; public class Api { }",
                          "package impl; public class Impl { }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { requires m1x; }",
                          "package test; public class Test { impl.Impl api; }");
        Path src_m3 = src.resolve("m3x");
        tb.writeJavaFiles(src_m3,
                          "module m3x { requires m1x; }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:35: compiler.err.package.not.visible: impl, (compiler.misc.not.def.access.not.exported: impl, m1x)",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);

        tb.writeJavaFiles(src_m1,
                          "module m1x { exports api; exports impl to m3x;}");

        log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        expected = Arrays.asList(
                "Test.java:1:35: compiler.err.package.not.visible: impl, (compiler.misc.not.def.access.not.exported.to.module: impl, m1x, m2x)",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testInaccessibleInExported(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { exports api; }",
                          "package api; class Api { }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { requires m1x; }",
                          "package test; public class Test { api.Api api; }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:38: compiler.err.not.def.public.cant.access: api.Api, api",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

//    @Test
    public void testInaccessibleUnnamedModule(Path base) throws Exception {
        Path jar = prepareTestJar(base, "package api; class Api { public static class Foo {} }");

        Path moduleSrc = base.resolve("module-src");
        Path m1x = moduleSrc.resolve("m1x");

        Path classes = base.resolve("classes");

        Files.createDirectories(classes);

        tb.writeJavaFiles(m1x,
                          "module m1x { }",
                          "package test; public class Test { api.Api api; api.Api.Foo api; }");

        List<String> log = new JavacTask(tb)
                .options("-classpath", jar.toString(),
                         "-XDrawDiagnostics")
                .outdir(classes)
                .files(findJavaFiles(moduleSrc))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:38: compiler.err.not.def.access.package.cant.access: api.Api, api, (compiler.misc.not.def.access.does.not.read.unnamed: api, m1x)",
                "Test.java:1:51: compiler.err.not.def.access.package.cant.access: api.Api, api, (compiler.misc.not.def.access.does.not.read.unnamed: api, m1x)",
                "2 errors");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testIndirectReferenceToUnnamedModule(Path base) throws Exception {
        Path jar = prepareTestJar(base, "package api; public class Api { public void test() {} }");

        Path moduleSrc = base.resolve("module-src");
        Path m1x = moduleSrc.resolve("m1x");
        Path auxiliary = moduleSrc.resolve("auxiliary");

        Path classes = base.resolve("classes");

        Files.createDirectories(classes);

        tb.writeJavaFiles(m1x,
                          "module m1x { requires auxiliary; }",
                          "package test; public class Test { { auxiliary.Auxiliary.get().test(); } }");

        tb.writeJavaFiles(auxiliary,
                          "module auxiliary { exports auxiliary; }",
                          "package auxiliary; public class Auxiliary { public static api.Api get() { return null; } }");

        List<String> log = new JavacTask(tb)
                .options("-classpath", jar.toString(),
                         "-XDrawDiagnostics",
                         "--add-reads", "auxiliary=ALL-UNNAMED",
                         "--module-source-path", moduleSrc.toString())
                .outdir(classes)
                .files(findJavaFiles(moduleSrc))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:62: compiler.err.not.def.access.class.intf.cant.access.reason: test(), api.Api, api, (compiler.misc.not.def.access.does.not.read.unnamed: api, m1x)",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    private Path prepareTestJar(Path base, String code) throws Exception {
        Path legacySrc = base.resolve("legacy-src");
        tb.writeJavaFiles(legacySrc, code);
        Path legacyClasses = base.resolve("legacy-classes");
        Files.createDirectories(legacyClasses);

        String log = new JavacTask(tb)
                .options()
                .outdir(legacyClasses)
                .files(findJavaFiles(legacySrc))
                .run()
                .writeAll()
                .getOutput(Task.OutputKind.DIRECT);

        if (!log.isEmpty()) {
            throw new Exception("unexpected output: " + log);
        }

        Path lib = base.resolve("lib");

        Files.createDirectories(lib);

        Path jar = lib.resolve("test-api-1.0.jar");

        new JarTask(tb, jar)
          .baseDir(legacyClasses)
          .files("api/Api.class")
          .run();

        return jar;
    }

    @Test
    public void testUnnamedModuleAccess(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { exports api to m2x; }",
                          "package api; class Api { }",
                          "package impl; class Impl { }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { requires m1x; }");
        Path modulepath = base.resolve("modulepath");
        tb.createDirectories(modulepath);

        new JavacTask(tb)
                .options("--module-source-path", src.toString())
                .outdir(modulepath)
                .files(findJavaFiles(src))
                .run()
                .writeAll();

        Path unnamedSrc = base.resolve("unnamedSrc");
        tb.writeJavaFiles(unnamedSrc,
                          "public class Test { api.Api api; impl.Impl impl; }");
        Path unnamedClasses = base.resolve("unnamed-classes");
        Files.createDirectories(unnamedClasses);

        List<String> log = new JavacTask(tb)
                .options("--module-path", modulepath.toString(),
                         "-XDrawDiagnostics")
                .outdir(unnamedClasses)
                .files(findJavaFiles(unnamedSrc))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:21: compiler.err.package.not.visible: api, (compiler.misc.not.def.access.does.not.read.from.unnamed: api, m1x)",
                "Test.java:1:34: compiler.err.package.not.visible: impl, (compiler.misc.not.def.access.does.not.read.from.unnamed: impl, m1x)",
                "2 errors"
        );

        if (!expected.equals(log)) {
            throw new Exception("unexpected output: " + log);
        }

        log = new JavacTask(tb)
                .options("--module-path", modulepath.toString(),
                         "--add-modules", "m1x",
                         "-XDrawDiagnostics")
                .outdir(unnamedClasses)
                .files(findJavaFiles(unnamedSrc))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        expected = Arrays.asList(
                "Test.java:1:21: compiler.err.package.not.visible: api, (compiler.misc.not.def.access.not.exported.to.module.from.unnamed: api, m1x)",
                "Test.java:1:34: compiler.err.package.not.visible: impl, (compiler.misc.not.def.access.not.exported.from.unnamed: impl, m1x)",
                "2 errors"
        );

        if (!expected.equals(log)) {
            throw new Exception("unexpected output: " + log);
        }
    }

    @Test
    public void testInImport(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { }",
                          "package api; public class Api { public String test() { return null; } }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { requires m1x; }",
                          "package test; import api.Api; public class Test { Api api; { api.test().length(); } }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:22: compiler.err.package.not.visible: api, (compiler.misc.not.def.access.not.exported: api, m1x)",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testInImportOnDemand(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { }",
                          "package api; public class Api { public String test() { return null; } }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { requires m1x; }",
                          "package test; import api.*; public class Test { Api api; { api.test().length(); } }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:22: compiler.err.package.not.visible: api, (compiler.misc.not.def.access.not.exported: api, m1x)",
                "Test.java:1:49: compiler.err.not.def.access.package.cant.access: api.Api, api, (compiler.misc.not.def.access.not.exported: api, m1x)",
                "2 errors");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testUnusedImportOnDemand1(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                          "package test; import javax.annotation.*; public class Test { }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--add-modules", "java.compiler")
                .outdir(classes)
                .files(findJavaFiles(src))
                .run()
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList("");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testUnusedImportOnDemand2(Path base) throws Exception {
        Path src = base.resolve("src");
        Path src_m1 = src.resolve("m1x");
        tb.writeJavaFiles(src_m1,
                          "module m1x { }",
                          "package api; public class Api { }");
        Path src_m2 = src.resolve("m2x");
        tb.writeJavaFiles(src_m2,
                          "module m2x { requires m1x; }",
                          "package test; import api.*; public class Test { }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        List<String> log = new JavacTask(tb)
                .options("-XDrawDiagnostics",
                         "--module-source-path", src.toString())
                .outdir(classes)
                .files(findJavaFiles(src))
                .run(Task.Expect.FAIL)
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        List<String> expected = Arrays.asList(
                "Test.java:1:22: compiler.err.package.not.visible: api, (compiler.misc.not.def.access.not.exported: api, m1x)",
                "1 error");

        if (!expected.equals(log))
            throw new Exception("expected output not found; actual: " + log);
    }

    @Test
    public void testClassPackageConflict(Path base) throws Exception {
        Path libSrc = base.resolve("libSrc");
        tb.writeJavaFiles(libSrc,
                          "package test.desktop; public class Any { }");
        Path libClasses = base.resolve("libClasses");
        tb.createDirectories(libClasses);

        new JavacTask(tb)
                .outdir(libClasses)
                .files(findJavaFiles(libSrc))
                .run()
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                          "package test; public class desktop { public static class Action { } }",
                          "package use; import test.desktop.*; public class Use { test.desktop.Action a; }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        new JavacTask(tb)
                .options("-XDrawDiagnostics")
                .classpath(libClasses)
                .outdir(classes)
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

    @Test
    public void testClassPackageConflictInUnnamed(Path base) throws Exception {
        Path libSrc = base.resolve("libSrc");
        tb.writeJavaFiles(libSrc,
                          "package desktop; public class Any { }");
        Path libClasses = base.resolve("libClasses");
        tb.createDirectories(libClasses);

        new JavacTask(tb)
                .outdir(libClasses)
                .files(findJavaFiles(libSrc))
                .run()
                .writeAll()
                .getOutputLines(Task.OutputKind.DIRECT);

        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                          "public class desktop { public static class Action { } }",
                          "import desktop.*; public class Use { desktop.Action a; }");
        Path classes = base.resolve("classes");
        tb.createDirectories(classes);

        new JavacTask(tb)
                .options("-XDrawDiagnostics")
                .classpath(libClasses)
                .outdir(classes)
                .files(findJavaFiles(src))
                .run()
                .writeAll();
    }

}