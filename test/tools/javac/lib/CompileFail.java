/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.util.*;

/*
 * Utility class to emulate jtreg @compile/fail, but also checking the specific
 * exit code, given as the first arg.
 */
public class CompileFail {
    public static void main(String... args) {
        if (args.length < 2)
            throw new IllegalArgumentException("insufficient args");
        int expected_rc = getReturnCode(args[0]);

        List<String> javacArgs = new ArrayList<>();
        javacArgs.addAll(Arrays.asList(
            "-bootclasspath", System.getProperty("sun.boot.class.path"),
            "-d", "."
        ));

        File testSrc = new File(System.getProperty("test.src"));
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.endsWith(".java"))
                javacArgs.add(new File(testSrc, arg).getPath());
            else
                javacArgs.add(arg);
        }

        int rc = com.sun.tools.javac.Main.compile(
            javacArgs.toArray(new String[javacArgs.size()]));

        if (rc != expected_rc)
            throw new Error("unexpected exit code: " + rc
                        + ", expected: " + expected_rc);
    }

    static int getReturnCode(String name) {
        switch (name) {
            case "OK":
                return EXIT_OK;

            case "ERROR":
                return EXIT_ERROR;

            case "CMDERR":
                return EXIT_CMDERR;

            case "SYSERR":
                return EXIT_SYSERR;

            case "ABNORMAL":
                return EXIT_ABNORMAL;

            default:
                throw new IllegalArgumentException(name);
        }
    }

    // The following is cut-n-paste from com.sun.tools.javac.main.Main
    static final int
        EXIT_OK = 0,        // Compilation completed with no errors.
        EXIT_ERROR = 1,     // Completed but reported errors.
        EXIT_CMDERR = 2,    // Bad command-line arguments
        EXIT_SYSERR = 3,    // System error or resource exhaustion.
        EXIT_ABNORMAL = 4;  // Compiler terminated abnormally
}
