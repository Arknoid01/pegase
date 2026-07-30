package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.OpenAiToolSchemaBuilder;

import com.pegasuscorp.orbe.tools.Tool;

import com.pegasuscorp.orbe.tools.ToolCallback;

import com.pegasuscorp.orbe.tools.ToolResult;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CalculatorToolTest {

    @Test
    public void execute_multiplyReturnsDeterministicSpeak() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        AtomicReference<ToolResult> out = new AtomicReference<>();
        tool.execute(RuntimeEnvironment.getApplication(),
                new JSONObject().put("expression", "12×4"),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult result) {
                        out.set(result);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                        fail("confirm");
                    }

                    @Override
                    public void onError(String error) {
                        fail(error);
                    }
                });
        assertNotNull(out.get());
        assertTrue(out.get().text.contains("48"));
        assertFalse(out.get().llmContext.isEmpty());
    }

    @Test
    public void execute_marginQuestion() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        AtomicReference<ToolResult> out = new AtomicReference<>();
        tool.execute(RuntimeEnvironment.getApplication(),
                new JSONObject()
                        .put("question",
                                "Combien je dois vendre un produit acheté 50 euros pour 36% de marge ?"),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult result) {
                        out.set(result);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                        fail("confirm");
                    }

                    @Override
                    public void onError(String error) {
                        fail(error);
                    }
                });
        assertNotNull(out.get());
        assertTrue(out.get().text.toLowerCase().contains("vend")
                || out.get().text.contains("78"));
    }

    @Test
    public void schema_containsExpressionParam() {
        JSONObject schema = OpenAiToolSchemaBuilder.toOpenAiTool(new CalculatorTool());
        JSONObject fn = schema.optJSONObject("function");
        assertNotNull(fn);
        assertEquals("calculator", fn.optString("name"));
        JSONObject props = fn.optJSONObject("parameters").optJSONObject("properties");
        assertTrue(props.has("expression"));
    }

    @Test
    public void execute_normalizedExpressions() throws Exception {
        assertCalc("119*5,5÷100", "6,545");
        assertCalc("119×5,5%", "6,545");
        assertCalc("119*5.5/100", "6,545");
        assertCalc("45+20+22+32", "119");
        assertCalc("11900÷5", "2380");
        assertCalc("50+36%", "50,36");
    }

    @Test
    public void execute_sameQuestionAndExpression_doesNotDuplicateInput() throws Exception {
        CalculatorTool tool = new CalculatorTool();
        AtomicReference<ToolResult> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        tool.execute(RuntimeEnvironment.getApplication(),
                new JSONObject()
                        .put("expression", "119*5,5/100")
                        .put("question", "119*5,5/100"),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult result) {
                        out.set(result);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                        fail("confirm");
                    }

                    @Override
                    public void onError(String error) {
                        err.set(error);
                    }
                });
        assertNull(err.get());
        assertNotNull(out.get());
        assertTrue(out.get().text.contains("6,545"));
    }

    @Test
    public void execute_emptyAndQuestionMark_cleanError() throws Exception {
        assertError("");
        assertError("?");
        assertError("calcule");
        assertError("combien");
    }

    @Test
    public void forTts_replacesDecimalPoint() {
        assertEquals("6,545", CalculatorTool.forTts("6.545"));
    }

    private static void assertCalc(String expression, String expectedFragment) throws Exception {
        CalculatorTool tool = new CalculatorTool();
        AtomicReference<ToolResult> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        tool.execute(RuntimeEnvironment.getApplication(),
                new JSONObject().put("expression", expression),
                new ToolCallback() {
                    @Override
                    public void onSuccess(ToolResult result) {
                        out.set(result);
                    }

                    @Override
                    public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                        fail("confirm");
                    }

                    @Override
                    public void onError(String error) {
                        err.set(error);
                    }
                });
        assertNull("unexpected error for " + expression + ": " + err.get(), err.get());
        assertNotNull(out.get());
        String compact = out.get().text
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replace(" ", "");
        String expectedCompact = expectedFragment.replace(" ", "");
        assertTrue("got=" + out.get().text + " expected to contain " + expectedFragment,
                compact.contains(expectedCompact));
        // TTS : pas de point décimal ASCII (virgule FR)
        assertFalse(out.get().text.matches("(?s).*\\d\\.\\d.*"));
    }

    private static void assertError(String expression) throws Exception {
        CalculatorTool tool = new CalculatorTool();
        AtomicReference<String> err = new AtomicReference<>();
        JSONObject params = new JSONObject();
        if (expression != null) params.put("expression", expression);
        tool.execute(RuntimeEnvironment.getApplication(), params, new ToolCallback() {
            @Override
            public void onSuccess(ToolResult result) {
                fail("expected error for « " + expression + " »");
            }

            @Override
            public void onConfirmNeeded(String question, Runnable onConfirm, Runnable onCancel) {
                fail("confirm");
            }

            @Override
            public void onError(String error) {
                err.set(error);
            }
        });
        assertNotNull(err.get());
        assertFalse(err.get().isEmpty());
    }
}
