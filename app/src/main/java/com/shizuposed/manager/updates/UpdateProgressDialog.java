package com.shizuposed.manager.updates;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.shizuposed.manager.R;

/**
 * A replacement for the deprecated ProgressDialog.
 *
 * Wraps an AlertDialog with a custom layout that has a title,
 * a status message, and a horizontal ProgressBar. The API
 * mirrors the parts of ProgressDialog this codebase used:
 * setTitle, setMessage, setMax, setProgress, show, dismiss,
 * isShowing.
 *
 * The progress bar is hidden until setProgress is called, so
 * an indeterminate phase (e.g. "Staging APK…" before the
 * download starts) shows just the message, and once real
 * progress is available the bar appears.
 */
public final class UpdateProgressDialog {

    private final AlertDialog dialog;
    private final TextView title;
    private final TextView message;
    private final ProgressBar bar;
    private boolean showing = false;

    public UpdateProgressDialog(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_update_progress, null);
        title = view.findViewById(R.id.tvProgressTitle);
        message = view.findViewById(R.id.tvProgressMessage);
        bar = view.findViewById(R.id.pbProgress);
        bar.setVisibility(View.GONE);   // hidden until real progress

        dialog = new AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create();
    }

    public void setTitle(CharSequence text) {
        title.setText(text);
    }

    public void setMessage(CharSequence text) {
        message.setText(text);
    }

    public void setMax(int max) {
        bar.setMax(max);
        bar.setVisibility(View.VISIBLE);
    }

    public void setProgress(int progress) {
        bar.setProgress(progress);
        bar.setVisibility(View.VISIBLE);
    }

    public void show() {
        if (showing) return;
        showing = true;
        dialog.show();
    }

    public void dismiss() {
        showing = false;
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {}
    }

    public boolean isShowing() {
        return showing && dialog.isShowing();
    }
}