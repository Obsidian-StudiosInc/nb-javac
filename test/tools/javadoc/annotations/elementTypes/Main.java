/*
 * Copyright (c) 2003, 2015, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 4904495
 * @summary Test an annotation type and annotations with elements of various
 *          types.
 * @library ../../lib
 * @modules jdk.javadoc
 * @compile  ../../lib/Tester.java Main.java
 * @run main Main
 */

import java.io.IOException;
import java.util.Arrays;
import com.sun.javadoc.*;
import com.sun.javadoc.AnnotationDesc.*;

public class Main extends Tester.Doclet {

    private static final Tester tester = new Tester("Main", "pkg1");

    public static void main(String[] args) throws IOException {
        tester.run();
        tester.verify();
    }

    public static boolean start(RootDoc root) {
        try {
            ClassDoc[] cds = root.classes();
            Arrays.sort(cds);
            for (ClassDoc cd : cds) {
                tester.printClass(cd);
                tester.println();
            }

            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
