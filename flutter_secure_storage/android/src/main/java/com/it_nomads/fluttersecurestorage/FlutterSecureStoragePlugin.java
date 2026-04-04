package com.it_nomads.fluttersecurestorage;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

public class FlutterSecureStoragePlugin implements MethodCallHandler, FlutterPlugin {

    private static final String TAG = "FlutterSecureStoragePlugin";
    private static final String DTAG = "FSS10";
    private MethodChannel channel;
    private FlutterSecureStorage secureStorage;
    private HandlerThread workerThread;
    private Handler workerThreadHandler;

    public void initInstance(BinaryMessenger messenger, Context context) {
        Log.d(DTAG, "Plugin initInstance() — creating FlutterSecureStorage");
        try {
            secureStorage = new FlutterSecureStorage(context);

            workerThread = new HandlerThread("com.it_nomads.fluttersecurestorage.worker");
            workerThread.start();
            workerThreadHandler = new Handler(workerThread.getLooper());

            channel = new MethodChannel(messenger, "plugins.it_nomads.com/flutter_secure_storage");
            channel.setMethodCallHandler(this);
        } catch (Exception e) {
            Log.e(TAG, "Registration failed", e);
        }
    }

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        initInstance(binding.getBinaryMessenger(), binding.getApplicationContext());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            workerThread.quitSafely();
            workerThread = null;

            channel.setMethodCallHandler(null);
            channel = null;
        }
        secureStorage = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result rawResult) {
        MethodResultWrapper result = new MethodResultWrapper(rawResult);
        // Run all method calls inside the worker thread instead of the platform thread.
        workerThreadHandler.post(new MethodRunner(call, result));
    }

    @SuppressWarnings("unchecked")
    private String getKeyFromCall(MethodCall call) {
        Map<String, Object> arguments = (Map<String, Object>) call.arguments;
        return secureStorage.addPrefixToKey((String) arguments.get("key"));
    }

    @SuppressWarnings("unchecked")
    private String getValueFromCall(MethodCall call) {
        Map<String, Object> arguments = (Map<String, Object>) call.arguments;
        return (String) arguments.get("value");
    }

    /**
     * MethodChannel.Result wrapper that responds on the platform thread.
     */
    static class MethodResultWrapper implements Result {

        private final Result methodResult;
        private final Handler handler = new Handler(Looper.getMainLooper());

        MethodResultWrapper(Result methodResult) {
            this.methodResult = methodResult;
        }

        @Override
        public void success(final Object result) {
            handler.post(() -> methodResult.success(result));
        }

        @Override
        public void error(@NonNull final String errorCode, final String errorMessage, final Object errorDetails) {
            handler.post(() -> methodResult.error(errorCode, errorMessage, errorDetails));
        }

        @Override
        public void notImplemented() {
            handler.post(methodResult::notImplemented);
        }
    }

    /**
     * Wraps the functionality of onMethodCall() in a Runnable for execution in the worker thread.
     */
    class MethodRunner implements Runnable {
        private final MethodCall call;
        private final Result result;

        MethodRunner(MethodCall call, Result result) {
            this.call = call;
            this.result = result;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void run() {
            Map<String, Object> options = (Map<String, Object>) ((Map<String, Object>) call.arguments).get("options");
            FlutterSecureStorageConfig config = new FlutterSecureStorageConfig(options);

            Log.d(DTAG, "MethodRunner.run() — method=" + call.method);

            secureStorage.initialize(config, new SecurePreferencesCallback<>() {
                @Override
                public void onSuccess(Void unused) {
                    Log.d(DTAG, "MethodRunner — initialize() succeeded, dispatching method=" + call.method);
                    try {
                        switch (call.method) {
                            case "write": {
                                String key = getKeyFromCall(call);
                                String value = getValueFromCall(call);
                                Log.d(DTAG, "MethodRunner — write key: " + key + " (value " + (value != null ? "present, len=" + value.length() : "NULL") + ")");

                                if (value != null) {
                                    secureStorage.write(key, value);
                                    result.success(null);
                                } else {
                                    Log.e(DTAG, "MethodRunner — write FAILED: value is null for key: " + key);
                                    result.error("null", null, null);
                                }
                                break;
                            }
                            case "read": {
                                String key = getKeyFromCall(call);
                                Log.d(DTAG, "MethodRunner — read key: " + key);

                                if (secureStorage.containsKey(key)) {
                                    String value = secureStorage.read(key);
                                    Log.d(DTAG, "MethodRunner — read success for key: " + key + " (value " + (value != null ? "present" : "null") + ")");
                                    result.success(value);
                                } else {
                                    Log.d(DTAG, "MethodRunner — read key not found: " + key);
                                    result.success(null);
                                }
                                break;
                            }
                            case "readAll": {
                                Map<String, String> all = secureStorage.readAll();
                                Log.d(DTAG, "MethodRunner — readAll returned " + all.size() + " entries");
                                result.success(all);
                                break;
                            }
                            case "containsKey": {
                                String key = getKeyFromCall(call);

                                boolean containsKey = secureStorage.containsKey(key);
                                Log.d(DTAG, "MethodRunner — containsKey(" + key + ") = " + containsKey);
                                result.success(containsKey);
                                break;
                            }
                            case "delete": {
                                String key = getKeyFromCall(call);
                                Log.d(DTAG, "MethodRunner — delete key: " + key);

                                secureStorage.delete(key);
                                result.success(null);
                                break;
                            }
                            case "deleteAll": {
                                Log.w(DTAG, "MethodRunner — deleteAll called");
                                secureStorage.deleteAll();
                                result.success(null);
                                break;
                            }
                            case "isBiometricAvailable": {
                                boolean available = secureStorage.isBiometricAvailable();
                                Log.d(DTAG, "MethodRunner — isBiometricAvailable = " + available);
                                result.success(available);
                                break;
                            }
                            case "isDeviceSecure": {
                                boolean secure = secureStorage.isDeviceSecure();
                                Log.d(DTAG, "MethodRunner — isDeviceSecure = " + secure);
                                result.success(secure);
                                break;
                            }
                            default:
                                Log.w(DTAG, "MethodRunner — unrecognized method: " + call.method);
                                result.notImplemented();
                                break;
                        }
                    } catch (Exception e) {
                        Log.e(DTAG, "MethodRunner — exception in " + call.method + ": " + e.getMessage());
                        if (config.shouldDeleteOnFailure()) {
                            try {
                                Log.w(DTAG, "MethodRunner — resetOnError triggered, deleting all data");
                                secureStorage.deleteAll();
                                result.success("Data has been reset");
                            } catch (Exception ex) {
                                Log.e(DTAG, "MethodRunner — resetOnError deleteAll also failed: " + ex.getMessage());
                                handleException(ex);
                            }
                        } else {
                            handleException(e);
                        }
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e(DTAG, "MethodRunner — initialize() FAILED for method=" + call.method + ": " + e.getMessage());
                    handleException(e);
                }
            });
        }


        private void handleException(Exception e) {
            StringWriter stringWriter = new StringWriter();
            e.printStackTrace(new PrintWriter(stringWriter));
            // Send exception message as the message field so Flutter can parse it
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
            result.error("Exception encountered", errorMessage, stringWriter.toString());
        }
    }
}