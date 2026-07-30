package com.pegasuscorp.orbe.bureau;

import org.junit.Test;

import static org.junit.Assert.*;

public class BureauCalcHelperTest {

    @Test
    public void multiply_simple() {
        BureauCalcHelper.Result r = BureauCalcHelper.trySolve("calcule 12 fois 4", null, true);
        assertNotNull(r);
        assertTrue(r.speak.contains("48"));
    }

    @Test
    public void add_simple() {
        BureauCalcHelper.Result r = BureauCalcHelper.trySolve("calcule 10 + 5", null, true);
        assertNotNull(r);
        assertTrue(r.speak.contains("15"));
    }

    @Test
    public void divide_simple() {
        BureauCalcHelper.Result r = BureauCalcHelper.trySolve("100 ÷ 4", null, true);
        assertNotNull(r);
        assertTrue(r.speak.toLowerCase().contains("25"));
    }

    @Test
    public void pureExpression_withParentheses() {
        Double v = BureauCalcHelper.evaluateExpression("12*(3+1)");
        assertNotNull(v);
        assertEquals(48.0, v, 0.001);
    }

    @Test
    public void margin_percentOnCost() {
        BureauCalcHelper.Result r = BureauCalcHelper.trySolve(
                "Combien je dois vendre un produit acheté 50 euros pour avoir 36% de marge ?",
                null, true);
        assertNotNull(r);
        assertTrue(r.speak.toLowerCase().contains("78") || r.speak.contains("78,1"));
    }

    @Test
    public void normalize_frDecimalsAndPercent() {
        assertEquals(6.545, BureauCalcHelper.evaluateExpression("119*5,5÷100"), 0.0001);
        assertEquals(6.545, BureauCalcHelper.evaluateExpression("119×5,5%"), 0.0001);
        assertEquals(6.545, BureauCalcHelper.evaluateExpression("119*5.5/100"), 0.0001);
        assertEquals(119.0, BureauCalcHelper.evaluateExpression("45+20+22+32"), 0.0001);
        assertEquals(2380.0, BureauCalcHelper.evaluateExpression("11900÷5"), 0.0001);
        assertEquals(50.36, BureauCalcHelper.evaluateExpression("50+36%"), 0.0001);
    }

    @Test
    public void evaluate_rejectsEmptyAndQuestionMark() {
        assertNull(BureauCalcHelper.evaluateExpression(""));
        assertNull(BureauCalcHelper.evaluateExpression(null));
        assertNull(BureauCalcHelper.evaluateExpression("?"));
        assertNull(BureauCalcHelper.evaluateExpression("???"));
    }

    @Test
    public void trySolve_pureNormalizedExpressions() {
        BureauCalcHelper.Result r = BureauCalcHelper.trySolve("119×5,5%", null, true);
        assertNotNull(r);
        assertTrue(r.speak.contains("6,545") || r.speak.contains("6.545"));
    }
}
