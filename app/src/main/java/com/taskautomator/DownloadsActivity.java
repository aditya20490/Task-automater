package com.taskautomator;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Simple download manager: every file the app downloaded, with progress. Tap to open. */
public class DownloadsActivity extends AppCompatActivity {

    private final List<DownloadItem> items = new ArrayList<>();
    private DownloadAdapter adapter;
    private TextView emptyText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        emptyText = findViewById(R.id.emptyText);
        RecyclerView list = findViewById(R.id.downloadList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.clearBtn).setOnClickListener(v -> {
            DownloadStore.clearFinished(this);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void refresh() {
        List<DownloadItem> fresh = DownloadStore.all(this);
        for (DownloadItem d : fresh) {
            // app was closed in the middle of a download
            if ("downloading".equals(d.status) && !Downloader.isActive(d.id)) {
                d.status = "failed";
                d.error = "Interrupted";
                DownloadStore.update(this, d);
            }
        }
        items.clear();
        items.addAll(fresh);
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    private void openFile(DownloadItem d) {
        if (!"done".equals(d.status) || d.uri.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(d.uri), d.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_LONG).show();
        }
    }

    private void removeDialog(final DownloadItem d) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(d.name)
                .setItems(new String[]{"Remove from list", "Delete file too"}, (dlg, which) -> {
                    if (which == 1) deleteFile(d);
                    DownloadStore.remove(this, d.id);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteFile(DownloadItem d) {
        try {
            if (!d.path.isEmpty()) //noinspection ResultOfMethodCallIgnored
                new File(d.path).delete();
            else if (!d.uri.isEmpty()) getContentResolver().delete(Uri.parse(d.uri), null, null);
        } catch (Exception ignored) { }
    }

    private class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView name, info;
            final LinearProgressIndicator bar;
            final View del;
            VH(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.nameText);
                info = v.findViewById(R.id.infoText);
                bar  = v.findViewById(R.id.bar);
                del  = v.findViewById(R.id.delBtn);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_download, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final DownloadItem d = items.get(position);
            h.name.setText(d.name);

            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(d.time));
            if ("downloading".equals(d.status)) {
                if (d.size > 0) {
                    int pct = (int) Math.min(100, d.done * 100 / d.size);
                    h.info.setText("Downloading… " + pct + "%  ·  " + size(d.done) + " of " + size(d.size));
                    h.bar.setVisibility(View.VISIBLE);
                    h.bar.setProgress(pct);
                } else {
                    h.info.setText("Downloading…  " + size(d.done));
                    h.bar.setVisibility(View.GONE);
                }
                h.del.setVisibility(View.INVISIBLE);
            } else if ("failed".equals(d.status)) {
                h.info.setText("Failed · " + d.error + "  ·  " + when);
                h.bar.setVisibility(View.GONE);
                h.del.setVisibility(View.VISIBLE);
            } else {
                h.info.setText(size(d.size) + "  ·  " + when);
                h.bar.setVisibility(View.GONE);
                h.del.setVisibility(View.VISIBLE);
            }

            h.itemView.setOnClickListener(v -> openFile(d));
            h.del.setOnClickListener(v -> removeDialog(d));
        }

        @Override
        public int getItemCount() { return items.size(); }
    }
}
