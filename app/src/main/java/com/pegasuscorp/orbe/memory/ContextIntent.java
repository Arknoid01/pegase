package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.tools.ToolTag;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Analyse locale avant appel LLM — budget et sections à charger. */
public final class ContextIntent {

    public final String intent;
    public final List<String> entityIds;
    public final boolean requiresTool;
    public final boolean requiresFreshData;
    public final int contextBudget;
    public final EnumSet<ProfileSection> profileSections;
    public final EnumSet<ToolTag> allowedTools;

    public ContextIntent(String intent, List<String> entityIds,
            boolean requiresTool, boolean requiresFreshData,
            int contextBudget, EnumSet<ProfileSection> profileSections,
            EnumSet<ToolTag> allowedTools) {
        this.intent = intent != null ? intent : "general";
        this.entityIds = entityIds != null ? entityIds : new ArrayList<>();
        this.requiresTool = requiresTool;
        this.requiresFreshData = requiresFreshData;
        this.contextBudget = contextBudget;
        this.profileSections = profileSections != null
                ? profileSections : EnumSet.of(ProfileSection.ESSENTIAL);
        this.allowedTools = allowedTools != null && !allowedTools.isEmpty()
                ? allowedTools : EnumSet.allOf(ToolTag.class);
    }

    public JSONObject toDebugJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("intent", intent);
            o.put("entities", new org.json.JSONArray(entityIds));
            o.put("requires_tool", requiresTool);
            o.put("requires_fresh_data", requiresFreshData);
            o.put("context_budget", contextBudget);
            org.json.JSONArray sections = new org.json.JSONArray();
            for (ProfileSection s : profileSections) sections.put(s.name());
            o.put("profile_sections", sections);
            org.json.JSONArray tools = new org.json.JSONArray();
            for (ToolTag t : allowedTools) tools.put(t.name());
            o.put("allowed_tools", tools);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
