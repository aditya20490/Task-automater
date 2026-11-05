package com.taskautomator;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Home screen: the list of saved projects. */
public class MainActivity extends AppCompatActivity {

    private ProjectStore store;
    private TextView emptyText;
    private final List<Project> projects = new ArrayList<>();
    private ProjectAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new ProjectStore(this);
        emptyText = findViewById(R.id.emptyText);

        RecyclerView list = findViewById(R.id.projectList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProjectAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.downloadsBtn).setOnClickListener(v ->
            startActivity(new Intent(this, DownloadsActivity.class)));

        findViewById(R.id.newBtn).setOnClickListener(v ->
            UiUtil.projectDialog(this, "New project", "", "https://", (name, url) -> {
                Intent i = new Intent(this, BrowserActivity.class);
                i.putExtra("mode", "record");
                i.putExtra("name", name);
                i.putExtra("url", url);
                startActivity(i);
            }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        projects.clear();
        projects.addAll(store.all());
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(projects.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class ProjectAdapter extends RecyclerView.Adapter<ProjectAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView name, url, meta;
            VH(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.nameText);
                url  = v.findViewById(R.id.urlText);
                meta = v.findViewById(R.id.metaText);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_project, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final Project p = projects.get(position);
            h.name.setText(p.name);
            h.url.setText(p.url);
            int n = p.steps.length();
            h.meta.setText(n + (n == 1 ? " step" : " steps") + "  ·  "
                    + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(p.created)));
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(MainActivity.this, ProjectActivity.class);
                i.putExtra("projectId", p.id);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() { return projects.size(); }
    }
}
