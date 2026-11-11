package com.npdep.npps4;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.Base64;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.android.PyApplication;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

public class App extends PyApplication {
    public static final String ADMIN_EXP_TOKEN_FILE = "npps4_admin_exp_token";

    @Override
    public void onCreate() {
        String datadir = Objects.requireNonNull(getExternalFilesDir("NPPS4")).toString();

        try {
            Os.setenv("PYTHONDONTWRITEBYTECODE", "1", true);
            Os.setenv("NPPS4_CONFIG_MAIN_DATADIR", datadir, false);
            Os.setenv("NPPS4_CONFIG_DATABASE_URL", "sqlite+aiosqlite:///" + datadir + "/main.sqlite3", false);
            Os.setenv("NPPS4_CONFIG_MAIN_SERVERDATA", datadir + "/server_data.json", false);
        } catch (ErrnoException ignored) {}
        loadManifestEnvVars();
        loadOrCreateAdminExpToken();

        super.onCreate();
        ((AndroidPlatform) Python.getPlatform()).redirectStdioToLogcat();

        // Copy server_data.json to the data directory
        Path targetFile = Paths.get(datadir + "/server_data.json");
        if (!Files.exists(targetFile)) {
            Python py = Python.getInstance();
            PyObject npps4 = py.getModule("npps4");
            Path npps4file = Paths.get(Objects.requireNonNull(npps4.get("__file__")).toString());
            Path sourceFile = npps4file.getParent().resolve("server_data.json");

            try {
                Files.copy(sourceFile, targetFile);
            } catch (IOException ignored) {}
        }
    }

    private void loadManifestEnvVars() {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(
                    getPackageName(),
                    PackageManager.GET_META_DATA
            );

            try {
                Os.setenv("NPPS4_CONFIG_MAIN_DATADIR", Objects.requireNonNull(getExternalFilesDir("NPPS4")).toString(), false);
            } catch (ErrnoException e) {
                Log.e("App", "Unable to set NPPS4_CONFIG_MAIN_DATADIR");
            }

            Bundle bundle = ai.metaData;
            if (bundle != null) {
                for (String key : bundle.keySet()) {
                    // Load keys prefixed with "env." to avoid
                    if (key.startsWith("env.")) {
                        String envKey = key.substring(4);
                        if (envKey.startsWith("NPPS4_ADMIN_EXP_")) {
                            throw new IllegalStateException(
                                    "Admin EXP secrets must not be stored in manifest metadata");
                        }
                        String envValue = bundle.getString(key);

                        if (envValue != null) {
                            try {
                                Os.setenv(envKey, envValue, false);
                                Log.d("App", "Set env var: " + envKey + " (value redacted)");
                            } catch (ErrnoException e) {
                                Log.e("App", "Failed to set env var: " + envKey, e);
                            }
                        }
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("App", "Failed to load meta-data", e);
        }
    }

    private void loadOrCreateAdminExpToken() {
        File directory = getNoBackupFilesDir();
        File tokenFile = new File(directory, ADMIN_EXP_TOKEN_FILE);

        try {
            if (!tokenFile.exists()) {
                byte[] randomBytes = new byte[32];
                new SecureRandom().nextBytes(randomBytes);
                String token = Base64.encodeToString(
                        randomBytes,
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
                );

                File temporaryFile = File.createTempFile("admin-exp-", ".tmp", directory);
                try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                    output.write(token.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                Os.chmod(temporaryFile.getAbsolutePath(), 0600);

                if (!temporaryFile.renameTo(tokenFile)) {
                    temporaryFile.delete();
                    if (!tokenFile.exists()) {
                        throw new IOException("Unable to atomically create local EXP admin token");
                    }
                }
            }

            Os.chmod(tokenFile.getAbsolutePath(), 0600);
            String token = readAdminExpToken(this);
            if (token == null) {
                throw new IOException("Local EXP admin token file is invalid");
            }
            Os.setenv("NPPS4_ADMIN_EXP_TOKEN", token, true);
        } catch (Exception e) {
            Log.e("App", "Unable to initialize local EXP admin token; EXP admin API remains disabled", e);
        }
    }

    public static String readAdminExpToken(Context context) {
        File tokenFile = new File(context.getNoBackupFilesDir(), ADMIN_EXP_TOKEN_FILE);
        if (!tokenFile.isFile() || tokenFile.length() > 128) {
            return null;
        }

        try (FileInputStream input = new FileInputStream(tokenFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            String token = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return token.matches("[A-Za-z0-9_-]{43}") ? token : null;
        } catch (IOException e) {
            return null;
        }
    }

}
