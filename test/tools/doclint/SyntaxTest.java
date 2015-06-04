/*
 * @test /nodynamiccopyright/
 * @bug 8004832
 * @summary Add new doclint package
 * @modules jdk.compiler/com.sun.tools.doclint
 * @build DocLintTester
 * @run main DocLintTester -Xmsgs:-syntax SyntaxTest.java
 * @run main DocLintTester -ref SyntaxTest.out SyntaxTest.java
 */

/** */
public class SyntaxTest {
    /**
     * a < b
     */
    public void syntax_error() { }
}

