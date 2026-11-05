package com.taskautomator;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

/** Project screen: shows every recorded step, lets you run / re-record / edit / delete. */
public class ProjectActivity extends AppCompatActivity {

    private ProjectStore store;
    private Project project;
    private StepAdapter adapter;
    private TextView titleText, urlText, metaText, emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project);

        store = new ProjectStore(this);
        titleText = findViewById(R.id.titleText);
        urlText   = findViewById(R.id.urlText);
        metaText  = findViewById(R.id.metaText);
        emptyText = findViewById(R.id.emptyText);

        RecyclerView list = findViewById(R.id.stepList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StepAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());

        findViewById(R.id.exportBtn).setOnClickListener(v -> {
            final Project p = project;
            Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
            new Thread(() -> Downloader.saveBytes(getApplicationContext(),
                    ProjectFile.toBytes(p), "application/json", ProjectFile.fileName(p))).start();
        });

        findViewById(R.id.editBtn).setOnClickListener(v ->
            UiUtil.projectDialog(this, "Edit project", project.name, project.url, (name, url) -> {
                project.name = name;
                project.url = url;
                store.save(project);
                bind();
            }));

        findViewById(R.id.deleteBtn).setOnClickListener(v ->
            new MaterialAlertDialogBuilder(this)
                .setTitle("Delete project?")
                .setMessage("“" + project.name + "” and its recorded steps will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    store.delete(project.id);
                    finish();
                })
                .show());

        findViewById(R.id.runBtn).setOnClickListener(v -> {
            if (project.steps.length() == 0) {
                Toast.makeText(this, "No steps yet — tap Re-record", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, BrowserActivity.class);
            i.putExtra("mode", "run");
            i.putExtra("projectId", project.id);
            startActivity(i);
        });

        findViewById(R.id.rerecordBtn).setOnClickListener(v -> {
            final Intent i = new Intent(this, BrowserActivity.class);
            i.putExtra("mode", "record");
            i.putExtra("projectId", project.id);
            if (project.steps.length() == 0) { startActivity(i); return; }
            new MaterialAlertDialogBuilder(this)
                .setTitle("Re-record this project?")
                .setMessage("The current " + project.steps.length() + " steps will be replaced once you finish recording.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Re-record", (d, w) -> startActivity(i))
                .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        project = store.get(getIntent().getStringExtra("projectId"));
        if (project == null) { finish(); return; }
        bind();
    }

    private void bind() {
        titleText.setText(project.name);
        urlText.setText(project.url);
        int n = project.steps.length();
        metaText.setText(n + (n == 1 ? " step" : " steps")
                + "  ·  every run starts with a fresh session (no cookies, no login)");
        emptyText.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ---------- turning a raw step into readable text ----------

    private static String titleOf(JSONObject s) {
        String type = s.optString("type");
        switch (type) {
            case "click": {
                String t = s.optString("text");
                return t.isEmpty() ? "Tap a " + s.optString("tag", "element") : "Tap “" + t + "”";
            }
            case "type": {
                String v = s.optBoolean("secret") ? "••••••••" : s.optString("value");
                String lab = s.optString("label");
                return "Type “" + v + "”" + (lab.isEmpty() ? "" : "  in  " + lab);
            }
            case "select":
                return "Choose “" + s.optString("value") + "”";
            case "key":
                return "Press Enter";
            default:
                return type;
        }
    }

    private class StepAdapter extends RecyclerView.Adapter<StepAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView num, title, detail;
            final View del;
            VH(@NonNull View v) {
                super(v);
                num    = v.findViewById(R.id.numText);
                title  = v.findViewById(R.id.titleText);
                detail = v.findViewById(R.id.detailText);
                del    = v.findViewById(R.id.delBtn);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_step, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final JSONObject s = project.steps.optJSONObject(position);
            if (s == null) return;
            h.num.setText(String.valueOf(position + 1));
            h.title.setText(titleOf(s));
            long wait = s.optLong("wait", 0);
            h.detail.setText(String.format(java.util.Locale.US, "after %.1fs · %s",
                    wait / 1000.0, s.optString("selector")));

            h.del.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                project.steps.remove(pos);
                store.save(project);
                bind();
            });

            // tap a "type" step to change the text it types
            if ("type".equals(s.optString("type")) && !s.optBoolean("secret")) {
                h.itemView.setOnClickListener(v ->
                    UiUtil.textDialog(ProjectActivity.this, "Edit text", "Text to type",
                        s.optString("value"), newText -> {
                            try {
                                s.put("value", newText);
                                store.save(project);
                                bind();
                            } catch (JSONException ignored) { }
                        }));
            } else {
                h.itemView.setOnClickListener(null);
                h.itemView.setClickable(false);
            }
        }

        @Override
        public int getItemCount() { return project == null ? 0 : project.steps.length(); }
    }
}
