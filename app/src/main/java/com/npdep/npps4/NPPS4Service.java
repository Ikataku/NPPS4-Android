package com.npdep.npps4;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NPPS4Service extends Service {
    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_STOPPING = 3;

    private volatile Exception lastError = null;
    private Runnable serverRunnable = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile int state = STATE_STOPPED;
    private final Object lifecycleLock = new Object();
    private boolean stopRequested = false;
    private boolean pythonPrepared = false;
    private int latestStartId = 0;
    private final INPPS4 binder = new INPPS4.Stub() {
        @Override
        public int getStatus() {
            return state;
        }

        @Override
        public void getStatusAsync(IStateCallbackResult resultCb) throws RemoteException {
            resultCb.onStateCallbackResult(state);
        }

        @Override
        public void shutdown() {
            synchronized (lifecycleLock) {
                if (serverRunnable == null) {
                    return;
                }

                stopRequested = true;
                state = STATE_STOPPING;
                if (pythonPrepared) {
                    Python py = Python.getInstance();
                    PyObject androidMain = py.getModule("android_main");
                    androidMain.callAttr("stop_server");
                }
            }
        }

        @Override
        public ConsoleText pollConsole() {
            synchronized (queue) {
                return queue.poll();
            }
        }

        @Override
        public String getLastError() {
            if (lastError != null) {
                String err = Log.getStackTraceString(lastError);
                lastError = null;
                return err;
            }

            return null;
        }
    };
    private final Queue<ConsoleText> queue = new LinkedList<>();

    public NPPS4Service() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String CHANNEL_ID = NPPS4Service.class.getName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "NPPS4", importance);
            channel.setDescription("NPPS4 Server Status");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        Runnable runnable = () -> {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("NPPS4 Server")
                    .setContentText("Server is running")
                    .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure this icon exists
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(startId, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(startId, notification);
            }

            try {
                Python py = Python.getInstance();
                PyObject androidMain = py.getModule("android_main");

                synchronized (lifecycleLock) {
                    if (stopRequested) {
                        return;
                    }
                    state = STATE_STARTING;
                    androidMain.callAttr("prepare_server");
                    pythonPrepared = true;
                }

                androidMain.callAttr("setup_server");
                synchronized (lifecycleLock) {
                    if (stopRequested) {
                        androidMain.callAttr("stop_server");
                        return;
                    }
                    state = STATE_RUNNING;
                }
                androidMain.callAttr("start_server");
            } catch (PyException e) {
                Log.e("NPPS4", "Python error", e);
                lastError = e;
            } finally {
                synchronized (lifecycleLock) {
                    state = STATE_STOPPED;
                    serverRunnable = null;
                    pythonPrepared = false;
                    stopRequested = false;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf(latestStartId);
                }
            }
        };
        synchronized (lifecycleLock) {
            latestStartId = startId;
            if (serverRunnable != null) {
                return START_STICKY;
            }
            stopRequested = false;
            pythonPrepared = false;
            serverRunnable = runnable;
            executor.submit(runnable);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder.asBinder();
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
    }
}
