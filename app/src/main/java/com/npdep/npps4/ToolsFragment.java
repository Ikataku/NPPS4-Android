package com.npdep.npps4;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ToolsFragment extends Fragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText userIdInput;
    private EditText totalExpInput;
    private Button applyButton;
    private TextView statusView;

    public ToolsFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_tools, container, false);
        userIdInput = view.findViewById(R.id.expAdminUserId);
        totalExpInput = view.findViewById(R.id.expAdminTotal);
        applyButton = view.findViewById(R.id.expAdminApply);
        statusView = view.findViewById(R.id.expAdminStatus);

        applyButton.setOnClickListener(v -> confirmAndApply());
        return view;
    }

    private void confirmAndApply() {
        final long userId;
        final long totalExp;
        try {
            userId = Long.parseLong(userIdInput.getText().toString().trim());
            totalExp = Long.parseLong(totalExpInput.getText().toString().trim());
            if (userId <= 0 || totalExp < 0) {
                throw new NumberFormatException("Values are outside the allowed range");
            }
        } catch (NumberFormatException e) {
            statusView.setText("Enter a positive User ID and a non-negative integer EXP total.");
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Set absolute EXP")
                .setMessage("Apply the exact cumulative EXP total to user " + userId + "?\n\n"
                        + "The current rank cannot be reduced. Crossing rank thresholds uses NPPS4's "
                        + "existing rank-up logic, including its LP and training-energy updates.")
                .setPositiveButton("Apply", (dialog, which) -> submitExp(userId, totalExp))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void submitExp(long userId, long totalExp) {
        if (applyButton == null || statusView == null) {
            return;
        }
        applyButton.setEnabled(false);
        statusView.setText("Applying EXP total...");

        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            HttpURLConnection connection = null;
            String message;
            try {
                String token = App.readAdminExpToken(appContext);
                if (token == null) {
                    throw new IOException("Local EXP admin token is unavailable. Restart NPPS4 and try again.");
                }

                connection = (HttpURLConnection) new URL(
                        "http://127.0.0.1:51376/admin/exp/total"
                ).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(15000);
                connection.setUseCaches(false);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject payload = new JSONObject();
                payload.put("user_id", userId);
                payload.put("total_exp", totalExp);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                InputStream responseStream = responseCode >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream();
                String responseBody = readResponse(responseStream);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    JSONObject response = new JSONObject(responseBody);
                    message = "Updated user " + response.getLong("user_id")
                            + ": Rank " + response.getInt("before_level")
                            + " -> " + response.getInt("after_level")
                            + ", EXP " + formatNumber(response.getLong("before_exp"))
                            + " -> " + formatNumber(response.getLong("after_exp"));
                } else {
                    String detail = responseBody;
                    try {
                        detail = new JSONObject(responseBody).optString(
                                "detail",
                                new JSONObject(responseBody).optString("message", responseBody)
                        );
                    } catch (Exception ignored) {
                    }
                    message = "EXP update failed (HTTP " + responseCode + "): " + detail;
                }
            } catch (Exception e) {
                message = "EXP update failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " - " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final String resultMessage = message;
            mainHandler.post(() -> {
                if (!isAdded() || statusView == null || applyButton == null) {
                    return;
                }
                statusView.setText(resultMessage);
                applyButton.setEnabled(true);
            });
        });
    }

    private static String readResponse(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String formatNumber(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    @Override
    public void onDestroyView() {
        userIdInput = null;
        totalExpInput = null;
        applyButton = null;
        statusView = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
