package net.gseek.fixtures.basics;

import java.io.IOException;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Java half of {@code R40}. **The same defect, written in the language whose rule Spring's
 * default is stated in.**
 *
 * <p>{@code R1} had to write two of its planted violations in Java because the Kotlin compiler
 * plugins fixed a Kotlin plant before the rule could see it — the plant arrived already
 * correct and the self-test would have been vacuous. This file is here for the mirror-image
 * reason: **the Kotlin version compiles without saying anything, and that silence is the
 * finding.** A Java version is the only way to show what the silence removed.
 *
 * <p><b>What to look at is the signature, not the body.</b> {@link #writeThenThrowChecked}
 * carries {@code throws IOException} because <em>javac will not compile it otherwise</em>.
 * That clause is not decoration:
 *
 * <ul>
 *   <li>it is written into the class file's {@code Exceptions} attribute, so a static rule can
 *       read it back through {@code Method.getExceptionTypes()} — and {@code R40} does;
 *   <li>every caller must either catch it or declare it, so the fact propagates up the call
 *       graph instead of stopping at the method that raises it;
 *   <li>a reviewer sees it in the diff that introduces it.
 * </ul>
 *
 * <p>None of those three is true of the Kotlin method beside it in
 * {@code KotlinRollbackProbe.writeThenThrow}, which raises the same exception type from inside
 * a transaction with the same annotation and the same result. The class file for the Kotlin
 * method has **no {@code Exceptions} attribute at all**, which is measured rather than
 * asserted — see {@code RollbackRuleTest}.
 *
 * <p><b>This is the answer to "was it a Spring problem or a Java problem".</b> Spring's default
 * rollback rule is a faithful reading of a Java language distinction. Kotlin removed the
 * distinction from the language and kept the runtime type, so the rule still fires and the
 * signal that used to accompany it is gone.
 *
 * <p>The package is {@code net.gseek.fixtures} for the same reason every other fixture here is:
 * outside Spring Boot's entity-scan root and outside
 * {@code TransactionBoundaryRulesTest}'s {@code importPackages("net.gseek.proxima")}.
 */
@TestComponent
public class JavaRollbackProbe {

    private final JdbcTemplate jdbc;

    public JavaRollbackProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>{@code throws IOException} is mandatory here.</b> Delete it and this file does not
     * compile — which is a stronger guarantee than any gate in this repository, and it is one
     * the Kotlin twin of this method does not have and cannot be given.
     */
    @Transactional
    public void writeThenThrowChecked(String ref) throws IOException {
        jdbc.update("insert into learner (external_ref) values (?)", ref);
        throw new IOException("checked, and the signature is forced to admit it");
    }

    /**
     * The unchecked case, for the control. No {@code throws} clause is required and none is
     * written, so this signature and a Kotlin one agree — as they should, because here there
     * is nothing for the Java type system to have said.
     */
    @Transactional
    public void writeThenThrowRuntime(String ref) {
        jdbc.update("insert into learner (external_ref) values (?)", ref);
        throw new IllegalStateException("unchecked, and the row should go");
    }

    /**
     * The remedy, in Java. {@code rollbackFor} is language-neutral: it names the runtime type
     * and does not care whether the compiler tracked it.
     */
    @Transactional(rollbackFor = Exception.class)
    public void writeThenThrowCheckedRollingBackForAnything(String ref) throws IOException {
        jdbc.update("insert into learner (external_ref) values (?)", ref);
        throw new IOException("checked, and this time the transaction is told to care");
    }
}
