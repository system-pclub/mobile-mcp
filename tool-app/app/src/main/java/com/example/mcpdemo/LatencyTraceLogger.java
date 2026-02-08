package com.example.mcpdemo;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;

public final class LatencyTraceLogger {
    private static final String FILE_NAME = "latency_trace.jsonl";
    private static final String TAG = "LatencyTrace";
    private static final String SOURCE = "tool-app";
    private static final Object LOCK = new Object();

    private LatencyTraceLogger() {
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE_NAME);
                FileWriter writer = new FileWriter(file, false);
                writer.write("");
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void logMark(
            Context context,
            String requestId,
            String capability,
            int runIndex,
            String step,
            long timestampNs,
            boolean success,
            String error,
            JSONObject extra
    ) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("record_type", "step");
            obj.put("source", SOURCE);
            obj.put("request_id", requestId);
            obj.put("capability", capability);
            obj.put("run_index", runIndex);
            obj.put("step", step);
            obj.put("start_ns", timestampNs);
            obj.put("end_ns", timestampNs);
            obj.put("duration_ms", 0.0);
            obj.put("success", success);
            obj.put("error", error == null ? JSONObject.NULL : error);
            obj.put("wall_time_ms", System.currentTimeMillis());
            mergeExtra(obj, extra);
        } catch (Exception ignored) {
        }
        append(context, obj);
    }

    public static void logSpan(
            Context context,
            String requestId,
            String capability,
            int runIndex,
            String step,
            long startNs,
            long endNs,
            boolean success,
            String error,
            JSONObject extra
    ) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("record_type", "step");
            obj.put("source", SOURCE);
            obj.put("request_id", requestId);
            obj.put("capability", capability);
            obj.put("run_index", runIndex);
            obj.put("step", step);
            obj.put("start_ns", startNs);
            obj.put("end_ns", endNs);
            obj.put("duration_ms", (endNs - startNs) / 1_000_000.0);
            obj.put("success", success);
            obj.put("error", error == null ? JSONObject.NULL : error);
            obj.put("wall_time_ms", System.currentTimeMillis());
            mergeExtra(obj, extra);
        } catch (Exception ignored) {
        }
        append(context, obj);
    }

    private static void mergeExtra(JSONObject target, JSONObject extra) {
        if (extra == null) {
            return;
        }
        try {
            java.util.Iterator<String> iter = extra.keys();
            while (iter.hasNext()) {
                String key = iter.next();
                target.put(key, extra.get(key));
            }
        } catch (Exception ignored) {
        }
    }

    private static void append(Context context, JSONObject obj) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE_NAME);
                FileWriter writer = new FileWriter(file, true);
                writer.write(obj.toString());
                writer.write("\n");
                writer.close();
            } catch (Exception ignored) {
            }
        }
        Log.d(TAG, obj.toString());
    }
}
