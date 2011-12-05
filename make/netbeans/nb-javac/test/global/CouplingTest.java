/*
 * Copyright 2003-2004 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */
package global;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author lahvac
 */
public class CouplingTest extends TestCase {

    public void test200122() throws Exception {
        String code = "package test; public class Test { void t() { new Runnable() { public void run() {} }; } }";
        List<String> fqns = compile(code);

        assertEquals(testCoupling(code, false, fqns), testCoupling(code, true, fqns));
    }
    
    //<editor-fold defaultstate="collapsed" desc=" Test Infrastructure ">
    static class MyFileObject extends SimpleJavaFileObject {
        private String text;

        public MyFileObject(String text) {
            super(URI.create("myfo:/Test.java"), JavaFileObject.Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    private File workingDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        workingDir = File.createTempFile("CouplingTest", "");

        workingDir.delete();
        workingDir.mkdirs();
    }

    @Override
    protected void tearDown() throws Exception {
        deleteRecursively(workingDir);
        super.tearDown();
    }

    private List<String> compile(String code) throws Exception {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        StandardJavaFileManager std = tool.getStandardFileManager(null, null, null);

        std.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(workingDir));

        final JavacTaskImpl ct = (JavacTaskImpl)tool.getTask(null, std, null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov", "-XDshouldStopPolicy=GENERATE"), null, Arrays.asList(new global.ErrorToleranceTest.MyFileObject(code)));
        Iterable<? extends CompilationUnitTree> cuts = ct.parse();

        ct.analyze();

        final List<String> result = new ArrayList<String>();

        new TreePathScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree node, Void p) {
                Element el = Trees.instance(ct).getElement(getCurrentPath());

                if (el != null && (el.getKind().isClass() || el.getKind().isInterface())) {
                    result.add(ct.getElements().getBinaryName((TypeElement) el).toString());
                }

                return super.visitClass(node, p);
            }
        }.scan(cuts, null);

        ct.generate();

        return result;
    }

    private Set<String> testCoupling(String code, boolean loadFromClasses, List<String> fqns) throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        StandardJavaFileManager std = tool.getStandardFileManager(null, null, null);

        if (loadFromClasses) {
            std.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(workingDir));
            std.setLocation(StandardLocation.CLASS_PATH, Collections.singleton(workingDir));
        }

        JavacTaskImpl ct = (JavacTaskImpl)tool.getTask(null, std, null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov", "-XDshouldStopPolicy=GENERATE"), null, Arrays.asList(new global.ErrorToleranceTest.MyFileObject(code)));

        if (loadFromClasses) {
            for (String fqn : fqns) {
                assertNotNull(fqn, ct.getElements().getTypeElementByBinaryName(fqn));
            }
        }

        ct.parse();
        ct.analyze();

        Set<String> classInfo = new HashSet<String>();

        for (String fqn : fqns) {
            ClassSymbol clazz = ct.getElements().getTypeElementByBinaryName(fqn);
            StringBuilder info = new StringBuilder();

            info.append(clazz.flatname.toString()).append(",");
            info.append(Long.toHexString(clazz.flags() & ~(Flags.FROMCLASS | Flags.APT_CLEANED))).append(",");
            info.append(clazz.hasOuterInstance());

            classInfo.add(info.toString());
        }

        return classInfo;
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                deleteRecursively(c);
            }
        }

        f.delete();
    }
    //</editor-fold>
}
