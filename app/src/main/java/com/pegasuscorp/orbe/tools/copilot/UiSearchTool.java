package com.pegasuscorp.orbe.tools.copilot;

import com.pegasuscorp.orbe.copilot.A11yUiExecutor;
import com.pegasuscorp.orbe.copilot.A11yUiMatcher;
import com.pegasuscorp.orbe.copilot.UiExplainHelper;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.ToolTag;
import com.pegasuscorp.orbe.tools.knowledge.WebSearchTool;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Recherche web sur un mot désigné à l'écran (v4).
 */
public final class UiSearchTool implements Tool {

  private final WebSearchTool webSearch = new WebSearchTool();

  @Override
  public String id() {
    return "ui_search";
  }

  @Override
  public ToolTag tag() {
    return ToolTag.UI;
  }

  @Override
  public String description() {
    return "ui_search(target:str, query:str) — Recherche web sur un mot visible à l'écran "
        + "(ouvre le navigateur). target = libellé visible ; agis sans question technique.";
  }

  @Override
  public void execute(Context ctx, JSONObject params, ToolCallback cb) {
    if (CopilotUiSupport.requireService(ctx, cb) == null) return;
    String query = params.optString("query", "").trim();
    String requested = A11yUiExecutor.parseCriteria(params).text;
    if (!TextUtils.isEmpty(requested)) {
      // target fourni = mot désigné à l'écran : il prime sur query (souvent une
      // paraphrase libre du LLM), et on surligne la cible même si query est rempli.
      A11yUiMatcher.Target target = UiExplainHelper.resolveTarget(ctx, params);
      if (target != null) {
        A11yUiExecutor.highlightTarget(ctx, target);
        // Le nœud a11y matché est souvent le paragraphe entier contenant le mot
        // (pas de nœud par mot) — chercher le mot demandé, pas tout le bloc.
        if (!TextUtils.isEmpty(target.text)
            && target.text.length() <= requested.length() + 24) {
          query = target.text;
        } else {
          query = requested;
        }
      } else if (query.isEmpty()) {
        query = requested;
      }
    }
    if (query.isEmpty()) {
      cb.onError("Indique le mot à chercher (target ou query).");
      return;
    }
    CopilotUiSupport.notifyActionInProgress(ctx, cb);
    try {
      webSearch.execute(ctx, new JSONObject().put("query", query), cb);
    } catch (Exception e) {
      cb.onError("Impossible de lancer la recherche.");
    }
  }
}
