package com.it_nomads.fluttersecurestorage;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;

/**
 * Helper class for managing migration backups.
 * Implements a rename-based backup strategy: copy to _BACKUP, mark complete, delete originals.
 */
public class MigrationBackup {
    private static final String TAG = "MigrationBackup";
    private static final String BACKUP_STATUS_KEY = "FlutterSecureStorageBackupStatus";
    private static final String BACKUP_SUFFIX = "_BACKUP";

    // TESTING: Migration step tracking
    private static final String MIGRATION_STEP_KEY = "FlutterSecureStorage_Migration_Test_Step";
    private static final String MIGRATION_LAST_ERROR_KEY = "FlutterSecureStorage_Migration_Last_Error";

    public static final String STATUS_STARTED = "started";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_DELETED = "deleted";

    /**
     * Creates backup by copying encrypted entries to <key>_BACKUP, then deleting originals.
     * Follows rename workflow: copy → mark complete → delete originals.
     *
     * @param dataSource SharedPreferences containing encrypted user data
     * @param keyStorage SharedPreferences containing wrapped AES keys
     * @param configSource SharedPreferences for backup status tracking
     * @param config Configuration object
     * @param keyPrefix Prefix to filter data keys
     */
    public static void createBackup(SharedPreferences dataSource,
                                   SharedPreferences keyStorage,
                                   SharedPreferences configSource,
                                   FlutterSecureStorageConfig config,
                                   String keyPrefix) {
        createBackup(dataSource, keyStorage, null, configSource, config, keyPrefix);
    }

    /**
     * Creates backup by copying encrypted entries to <key>_BACKUP, then deleting originals.
     * Follows rename workflow: copy → mark complete → delete originals.
     * Can also backup ESP data if espSource is provided.
     *
     * @param dataSource SharedPreferences containing encrypted user data
     * @param keyStorage SharedPreferences containing wrapped AES keys
     * @param espSource EncryptedSharedPreferences source (can be null)
     * @param configSource SharedPreferences for backup status tracking
     * @param config Configuration object
     * @param keyPrefix Prefix to filter data keys
     */
    public static void createBackup(SharedPreferences dataSource,
                                   SharedPreferences keyStorage,
                                   SharedPreferences espSource,
                                   SharedPreferences configSource,
                                   FlutterSecureStorageConfig config,
                                   String keyPrefix) {
        // Check if backup already exists - skip if complete or deleted
        String status = getBackupStatus(configSource, config);
        if (STATUS_COMPLETE.equals(status) || STATUS_DELETED.equals(status)) {
            Log.d(TAG, "Backup already exists (status: " + status + "), skipping");
            return;
        }

        // If status is "started", delete incomplete backup and start fresh
        if (STATUS_STARTED.equals(status)) {
            Log.w(TAG, "Found incomplete backup (status: started), deleting and restarting");
            deleteBackupData(dataSource, keyStorage, espSource, keyPrefix);
        }

        Log.i(TAG, "Starting backup creation (rename operation)...");

        // Mark backup as started
        setBackupStatus(configSource, config, STATUS_STARTED);

        int dataCount = 0;
        int keyCount = 0;
        int espCount = 0;

        // Step 1a: Copy ESP data to _BACKUP within ESP itself if ESP source provided
        if (espSource != null) {
            Log.i(TAG, "Backing up EncryptedSharedPreferences data with _BACKUP suffix...");
            try {
                SharedPreferences.Editor espEditor = espSource.edit();
                for (Map.Entry<String, ?> entry : espSource.getAll().entrySet()) {
                    String key = entry.getKey();
                    if (entry.getValue() instanceof String && key.contains(keyPrefix) && !key.endsWith(BACKUP_SUFFIX)) {
                        // Copy ESP data: <key> → <key>_BACKUP (within ESP storage)
                        // ESP handles encryption, so the backed-up key remains encrypted
                        espEditor.putString(key + BACKUP_SUFFIX, (String) entry.getValue());
                        espCount++;
                    }
                }
                if (!espEditor.commit()) {
                    throw new RuntimeException("Failed to copy ESP data to backup");
                }
                Log.i(TAG, "Backed up " + espCount + " items in ESP");
            } catch (Exception espError) {
                // ESP is corrupted and can't be read - skip ESP backup
                // The migration will proceed with algorithm mismatch handling
                Log.w(TAG, "ESP backup failed (ESP corrupted): " + espError.getMessage());
                Log.w(TAG, "Skipping ESP backup - migration will use algorithm mismatch recovery");
                espCount = 0;
            }
        }

        // Step 1b: Copy encrypted user data to _BACKUP
        SharedPreferences.Editor dataEditor = dataSource.edit();
        for (Map.Entry<String, ?> entry : dataSource.getAll().entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() instanceof String && key.contains(keyPrefix) && !key.endsWith(BACKUP_SUFFIX)) {
                // Simple string copy: <key> → <key>_BACKUP
                dataEditor.putString(key + BACKUP_SUFFIX, (String) entry.getValue());
                dataCount++;
            }
        }
        if (!dataEditor.commit()) {
            throw new RuntimeException("Failed to copy encrypted data to backup");
        }

        // Step 2: Copy wrapped AES keys to _BACKUP
        SharedPreferences.Editor keyEditor = keyStorage.edit();
        for (Map.Entry<String, ?> entry : keyStorage.getAll().entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() instanceof String && !key.endsWith(BACKUP_SUFFIX)) {
                // Simple string copy: <key> → <key>_BACKUP
                keyEditor.putString(key + BACKUP_SUFFIX, (String) entry.getValue());
                keyCount++;
            }
        }
        if (!keyEditor.commit()) {
            throw new RuntimeException("Failed to copy wrapped keys to backup");
        }

        // Step 3: Mark backup as complete (critical safety point)
        setBackupStatus(configSource, config, STATUS_COMPLETE);
        Log.i(TAG, "Backup marked complete - original data still exists");

        // Step 4: Now safe to delete originals (rename operation)
        deleteOriginalData(dataSource, keyStorage, keyPrefix);

        // Step 5: Delete original ESP keys (keep _BACKUP ones)
        if (espSource != null && espCount > 0) {
            Log.i(TAG, "Deleting original ESP keys (keeping _BACKUP)...");
            SharedPreferences.Editor espEditor = espSource.edit();
            for (Map.Entry<String, ?> entry : espSource.getAll().entrySet()) {
                String key = entry.getKey();
                // Delete original keys, keep _BACKUP keys
                if (key.contains(keyPrefix) && !key.endsWith(BACKUP_SUFFIX)) {
                    espEditor.remove(key);
                }
            }
            espEditor.commit();
            Log.i(TAG, "Original ESP keys deleted (renamed to _BACKUP)");
        }

        Log.i(TAG, "Backup created (rename complete): " + dataCount + " data items, " +
             keyCount + " wrapped keys, " + espCount + " ESP items - originals deleted, only _BACKUP exists");
    }

    /**
     * Deletes all _BACKUP entries from storage.
     * Sets backup status to "deleted" in configSource.
     *
     * @param dataSource SharedPreferences containing user data
     * @param keyStorage SharedPreferences containing wrapped keys
     * @param configSource SharedPreferences for status tracking
     * @param config Configuration object
     * @param keyPrefix Prefix to filter data keys
     */
    public static void deleteBackup(SharedPreferences dataSource,
                                   SharedPreferences keyStorage,
                                   SharedPreferences configSource,
                                   FlutterSecureStorageConfig config,
                                   String keyPrefix) {
        deleteBackup(dataSource, keyStorage, null, configSource, config, keyPrefix);
    }

    /**
     * Deletes all _BACKUP entries from storage, including ESP.
     * Sets backup status to "deleted" in configSource.
     *
     * @param dataSource SharedPreferences containing user data
     * @param keyStorage SharedPreferences containing wrapped keys
     * @param espSource EncryptedSharedPreferences source (can be null)
     * @param configSource SharedPreferences for status tracking
     * @param config Configuration object
     * @param keyPrefix Prefix to filter data keys
     */
    public static void deleteBackup(SharedPreferences dataSource,
                                   SharedPreferences keyStorage,
                                   SharedPreferences espSource,
                                   SharedPreferences configSource,
                                   FlutterSecureStorageConfig config,
                                   String keyPrefix) {
        deleteBackupData(dataSource, keyStorage, espSource, keyPrefix);

        // Mark backup as deleted
        setBackupStatus(configSource, config, STATUS_DELETED);

        Log.d(TAG, "Backup deleted and marked as deleted");
    }

    /**
     * Sets backup status in configSource.
     * Only writes if config.shouldMigrateWithBackup() is true.
     *
     * @param configSource SharedPreferences for status tracking
     * @param config Configuration object
     * @param status Status string (started/complete/deleted)
     */
    public static void setBackupStatus(SharedPreferences configSource,
                                      FlutterSecureStorageConfig config,
                                      String status) {
        if (!config.shouldMigrateWithBackup()) {
            return;  // Don't write backup status if backup disabled
        }

        configSource.edit()
            .putString(BACKUP_STATUS_KEY, status)
            .commit();
    }

    /**
     * Gets backup status from configSource.
     *
     * @param configSource SharedPreferences for status tracking
     * @param config Configuration object
     * @return Status string or null if not present
     */
    public static String getBackupStatus(SharedPreferences configSource,
                                        FlutterSecureStorageConfig config) {
        return configSource.getString(BACKUP_STATUS_KEY, null);
    }

    /**
     * Checks if backup exists (status is "complete").
     *
     * @param configSource SharedPreferences for status tracking
     * @param config Configuration object
     * @return true if backup status is "complete"
     */
    public static boolean hasBackup(SharedPreferences configSource,
                                   FlutterSecureStorageConfig config) {
        String status = getBackupStatus(configSource, config);
        return STATUS_COMPLETE.equals(status);
    }

    /**
     * Deletes _BACKUP entries from storage (without updating status).
     * Internal helper method.
     */
    private static void deleteBackupData(SharedPreferences dataSource,
                                        SharedPreferences keyStorage,
                                        String keyPrefix) {
        deleteBackupData(dataSource, keyStorage, null, keyPrefix);
    }

    /**
     * Deletes _BACKUP entries from storage, including ESP (without updating status).
     * Internal helper method.
     */
    private static void deleteBackupData(SharedPreferences dataSource,
                                        SharedPreferences keyStorage,
                                        SharedPreferences espSource,
                                        String keyPrefix) {
        int dataCount = 0;
        int keyCount = 0;
        int espCount = 0;

        // Delete _BACKUP keys from ESP if provided
        if (espSource != null) {
            SharedPreferences.Editor espEditor = espSource.edit();
            for (Map.Entry<String, ?> entry : espSource.getAll().entrySet()) {
                String key = entry.getKey();
                if (key.endsWith(BACKUP_SUFFIX) && key.contains(keyPrefix)) {
                    espEditor.remove(key);
                    espCount++;
                }
            }
            espEditor.commit();
        }

        // Delete _BACKUP keys from dataSource
        SharedPreferences.Editor dataEditor = dataSource.edit();
        for (Map.Entry<String, ?> entry : dataSource.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(BACKUP_SUFFIX) && key.contains(keyPrefix)) {
                dataEditor.remove(key);
                dataCount++;
            }
        }
        dataEditor.commit();

        // Delete _BACKUP keys from keyStorage
        SharedPreferences.Editor keyEditor = keyStorage.edit();
        for (Map.Entry<String, ?> entry : keyStorage.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(BACKUP_SUFFIX)) {
                keyEditor.remove(key);
                keyCount++;
            }
        }
        keyEditor.commit();

        if (dataCount > 0 || keyCount > 0 || espCount > 0) {
            Log.d(TAG, "Deleted " + dataCount + " data _BACKUP entries, " + keyCount + " key _BACKUP entries, " + espCount + " ESP _BACKUP entries");
        }
    }

    /**
     * Deletes original (non-_BACKUP) entries from storage.
     * This completes the rename operation after backup is marked complete.
     * Internal helper method.
     */
    private static void deleteOriginalData(SharedPreferences dataSource,
                                          SharedPreferences keyStorage,
                                          String keyPrefix) {
        int dataCount = 0;
        int keyCount = 0;

        // Delete original keys from dataSource
        SharedPreferences.Editor dataEditor = dataSource.edit();
        for (Map.Entry<String, ?> entry : dataSource.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith(BACKUP_SUFFIX) && key.contains(keyPrefix)) {
                dataEditor.remove(key);
                dataCount++;
            }
        }
        dataEditor.commit();

        // Delete original keys from keyStorage
        SharedPreferences.Editor keyEditor = keyStorage.edit();
        for (Map.Entry<String, ?> entry : keyStorage.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith(BACKUP_SUFFIX)) {
                keyEditor.remove(key);
                keyCount++;
            }
        }
        keyEditor.commit();

        Log.d(TAG, "Deleted " + dataCount + " original data entries, " + keyCount + " original key entries");
    }

    // ============================================================================
    // TESTING: Migration Step Tracking for Systematic Failure Testing
    // ============================================================================

    /**
     * Gets the current migration test step number.
     * Used for systematic testing of migration failures at each step.
     * @param configSource SharedPreferences for storing step number
     * @return Current step number (0-7), or -1 if testing complete
     */
    public static int getMigrationTestStep(SharedPreferences configSource) {
        return configSource.getInt(MIGRATION_STEP_KEY, 0);
    }

    /**
     * Sets the current migration test step number.
     * @param configSource SharedPreferences for storing step number
     * @param step Step number (0-7), or -1 to mark testing complete
     */
    public static void setMigrationTestStep(SharedPreferences configSource, int step) {
        SharedPreferences.Editor editor = configSource.edit();
        editor.putInt(MIGRATION_STEP_KEY, step);
        editor.commit();
        Log.i(TAG, "Migration test step set to: " + step);
    }

    /**
     * Increments the migration test step and returns the new value.
     * If step reaches max (7), sets to -1 to indicate testing complete.
     * @param configSource SharedPreferences for storing step number
     * @param maxStep Maximum step number (usually 7)
     * @return New step number, or -1 if testing complete
     */
    public static int incrementMigrationTestStep(SharedPreferences configSource, int maxStep) {
        int currentStep = getMigrationTestStep(configSource);
        int nextStep = currentStep >= maxStep ? -1 : currentStep + 1;
        setMigrationTestStep(configSource, nextStep);
        return nextStep;
    }

    /**
     * Records the last migration error for diagnostic purposes.
     * @param configSource SharedPreferences for storing error
     * @param step Step number where error occurred
     * @param error Error message
     */
    public static void recordMigrationError(SharedPreferences configSource, int step, String error) {
        SharedPreferences.Editor editor = configSource.edit();
        editor.putString(MIGRATION_LAST_ERROR_KEY, "Step " + step + ": " + error);
        editor.commit();
        Log.e(TAG, "Recorded migration error at step " + step + ": " + error);
    }

    /**
     * Gets the last recorded migration error.
     * @param configSource SharedPreferences for storing error
     * @return Last error message, or null if none
     */
    public static String getLastMigrationError(SharedPreferences configSource) {
        return configSource.getString(MIGRATION_LAST_ERROR_KEY, null);
    }

    /**
     * Resets migration testing state.
     * @param configSource SharedPreferences for storing state
     */
    public static void resetMigrationTesting(SharedPreferences configSource) {
        SharedPreferences.Editor editor = configSource.edit();
        editor.remove(MIGRATION_STEP_KEY);
        editor.remove(MIGRATION_LAST_ERROR_KEY);
        editor.commit();
        Log.i(TAG, "Migration testing state reset");
    }
}
