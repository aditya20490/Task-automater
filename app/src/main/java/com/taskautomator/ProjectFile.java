package com.taskautomator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Turns a project into a shareable .json task file, and back. */
class ProjectFile {

    static byte[] toBytes(Project p) {
        try {
            JSONObject o = new JSONObject();
            o.put("format", "task-automator");
            o.put("version", 1);
            o.put("exported", System.currentTimeMillis());
            o.put("project", p.toJson());
            return o.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return p.toJson().toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static String fileName(Project p) {
        String n = p.name == null ? "" : p.name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (n.isEmpty()) n = "task";
        return n + ".task.json";
    }

    /** Reads a task file. Always gets a brand-new id so it never overwrites an existing project. */
    static Project parse(String text) throws Exception {
        JSONObject o = new JSONObject(text.trim());
        JSONObject po = o.has("project") ? o.getJSONObject("project") : o;
        JSONArray steps = po.optJSONArray("steps");
        String url = po.optString("url", "");
        if (steps == null || url.isEmpty()) throw new Exception("Not a task file");
        Project p = Project.fromJson(po);
        p.id = UUID.randomUUID().toString();
        return p;
    }
}
