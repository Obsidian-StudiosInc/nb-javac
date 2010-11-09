/*
 * @test /nodynamiccopyright/
 * @bug     6881115 6976649
 * @summary javac permits nested anno w/o mandatory attrs => IncompleteAnnotationException
 * @author  mcimadamore
 * @compile/fail/ref=T6881115.out -XDrawDiagnostics T6881115.java
 */

@interface A {
    B b() default @B(b2 = 1, b2 = 2);
    B[] b_arr() default {@B(), @B(b2 = 1, b2 = 2)};
}
@interface B {
    String b1();
    int b2();
}
@A(b = @B(b2 = 1, b2 = 2),
   b_arr = {@B(), @B(b2 = 1, b2 = 2)})
class T6881115</*308 @A(b = @B(b2 = 1, b2 = 2),
                  b_arr = {@B(), @B(b2 = 1, b2 = 2)})*/ X> {}
