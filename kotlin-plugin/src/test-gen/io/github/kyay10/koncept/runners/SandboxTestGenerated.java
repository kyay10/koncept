

package io.github.kyay10.koncept.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link io.github.kyay10.koncept.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("src/testData/sandbox")
@TestDataPath("$PROJECT_ROOT")
public class SandboxTestGenerated extends AbstractSandboxTest {
    @Test
    public void testAllFilesPresentInSandbox() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("src/testData/sandbox"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
    }

    @Test
    @TestMetadata("display concept.kt")
    public void testDisplay_concept() throws Exception {
        runTest("src/testData/sandbox/display concept.kt");
    }

    @Test
    @TestMetadata("general sandbox.kt")
    public void testGeneral_sandbox() throws Exception {
        runTest("src/testData/sandbox/general sandbox.kt");
    }

    @Test
    @TestMetadata("overload resolution.kt")
    public void testOverload_resolution() throws Exception {
        runTest("src/testData/sandbox/overload resolution.kt");
    }
}
