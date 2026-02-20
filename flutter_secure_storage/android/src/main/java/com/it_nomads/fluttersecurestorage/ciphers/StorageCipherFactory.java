package com.it_nomads.fluttersecurestorage.ciphers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.it_nomads.fluttersecurestorage.FlutterSecureStorageConfig;

import javax.crypto.Cipher;

public class StorageCipherFactory {
    private static final String ELEMENT_PREFERENCES_ALGORITHM_PREFIX = "FlutterSecureSAlgorithm";
    private static final String ELEMENT_PREFERENCES_ALGORITHM_KEY = ELEMENT_PREFERENCES_ALGORITHM_PREFIX + "Key";
    private static final String ELEMENT_PREFERENCES_ALGORITHM_STORAGE = ELEMENT_PREFERENCES_ALGORITHM_PREFIX + "Storage";
    private static final KeyCipherAlgorithm DEFAULT_KEY_ALGORITHM = KeyCipherAlgorithm.RSA_ECB_PKCS1Padding;
    private static final StorageCipherAlgorithm DEFAULT_STORAGE_ALGORITHM = StorageCipherAlgorithm.AES_CBC_PKCS7Padding;

    private final KeyCipherAlgorithm savedKeyAlgorithm;
    private final StorageCipherAlgorithm savedStorageAlgorithm;
    private final KeyCipherAlgorithm currentKeyAlgorithm;
    private final StorageCipherAlgorithm currentStorageAlgorithm;
    private final FlutterSecureStorageConfig config;

    public StorageCipherFactory(SharedPreferences configSource, String keyCipherAlgorithm, String storageCipherAlgorithm, FlutterSecureStorageConfig config) {
        this.config = config;
        final String savedKeyCipherAlgorithm = configSource.getString(ELEMENT_PREFERENCES_ALGORITHM_KEY, null);
        final String savedStorageCipherAlgorithm = configSource.getString(ELEMENT_PREFERENCES_ALGORITHM_STORAGE, null);

        if (savedKeyCipherAlgorithm == null || savedStorageCipherAlgorithm == null) {
            // Migration from v9.2.4 or v10.0.0-beta.4:
            // No algorithm markers exist in SharedPreferences, which means the data was encrypted
            // with the historical v9.2.4 defaults. We must use these defaults to decrypt the old
            // data, even if the current config specifies different algorithms.
            // After successful decryption, the data will be re-encrypted with current algorithms
            // (if they differ) via the migration flow in handleKeyMismatch().
            savedKeyAlgorithm = DEFAULT_KEY_ALGORITHM;        // RSA_ECB_PKCS1Padding
            savedStorageAlgorithm = DEFAULT_STORAGE_ALGORITHM; // AES_CBC_PKCS7Padding
        } else {
            savedKeyAlgorithm = KeyCipherAlgorithm.fromString(savedKeyCipherAlgorithm);
            savedStorageAlgorithm = StorageCipherAlgorithm.fromString(savedStorageCipherAlgorithm);
        }

        final StorageCipherAlgorithm currentStorageAlgorithmTmp = StorageCipherAlgorithm.fromString(storageCipherAlgorithm);
        currentStorageAlgorithm = (currentStorageAlgorithmTmp.minVersionCode <= Build.VERSION.SDK_INT) ? currentStorageAlgorithmTmp : DEFAULT_STORAGE_ALGORITHM;

        // Set current key algorithm with version check
        final KeyCipherAlgorithm currentKeyAlgorithmTmp = KeyCipherAlgorithm.fromString(keyCipherAlgorithm);
        currentKeyAlgorithm = (currentKeyAlgorithmTmp.minVersionCode <= Build.VERSION.SDK_INT) ? currentKeyAlgorithmTmp : DEFAULT_KEY_ALGORITHM;

        if (savedKeyCipherAlgorithm == null || savedStorageCipherAlgorithm == null) {
            // Don't write algorithm markers during recovery mode (read-only) or during
            // migrateWithBackup (the migration flow writes them at step 7 after success).
            if (!config.shouldMigrateWithBackup() && !config.isRecoveryMode()) {
                final SharedPreferences.Editor source = configSource.edit();
                storeCurrentAlgorithms(source);
                source.apply();
            }
        }
    }

    public boolean requiresReEncryption() {
        return savedKeyAlgorithm != currentKeyAlgorithm || savedStorageAlgorithm != currentStorageAlgorithm;
    }

    public boolean changedKeyAlgorithm() {
        return savedKeyAlgorithm != currentKeyAlgorithm;
    }

    public StorageCipher getSavedStorageCipher(Context context, Cipher cipher) throws Exception {
        final KeyCipher keyCipher = savedKeyAlgorithm.keyCipher.apply(context, config);
        return createStorageCipher(context, keyCipher, cipher, savedStorageAlgorithm);
    }

    public StorageCipher getCurrentStorageCipher(Context context, Cipher cipher) throws Exception {
        final KeyCipher keyCipher = currentKeyAlgorithm.keyCipher.apply(context, config);
        return createStorageCipher(context, keyCipher, cipher, currentStorageAlgorithm);
    }

    /**
     * Read-only variant: creates a StorageCipher using the provided wrapped key blob directly
     * instead of reading from SharedPreferences. Does not write to any SharedPreferences.
     * Used exclusively by recovery mode to try specific key blobs (current or _BACKUP) without
     * modifying persistent storage.
     *
     * @param context          Android context
     * @param cipher           Cipher instance (may be null for some algorithms)
     * @param wrappedKeyBase64 Base64-encoded wrapped AES key blob to unwrap
     * @return StorageCipher initialized with the unwrapped key
     * @throws Exception if the key blob cannot be unwrapped with the current key algorithm
     */
    public StorageCipher getCurrentStorageCipherWithBlob(Context context, Cipher cipher, String wrappedKeyBase64) throws Exception {
        final KeyCipher keyCipher = currentKeyAlgorithm.keyCipher.apply(context, config);
        return createStorageCipherWithBlob(keyCipher, cipher, currentStorageAlgorithm, wrappedKeyBase64);
    }

    /**
     * Read-only variant of createStorageCipher: uses the provided key blob directly
     * instead of reading from SharedPreferences. Delegates to read-only constructors
     * on StorageCipherImplementationGCM / StorageCipherImplementationAES18.
     */
    private StorageCipher createStorageCipherWithBlob(KeyCipher keyCipher, Cipher cipher,
                                                       StorageCipherAlgorithm algorithm,
                                                       String wrappedKeyBase64) throws Exception {
        if (algorithm == StorageCipherAlgorithm.AES_GCM_NoPadding) {
            if (isKeyStoreKeyCipher(keyCipher)) {
                // Biometric/KeyStore-based: no RSA-wrapped blob, can't use blob-based init
                throw new Exception("Blob-based init not supported for KeyStore cipher");
            }
            return new StorageCipherImplementationGCM(keyCipher, cipher, wrappedKeyBase64);
        }
        if (algorithm == StorageCipherAlgorithm.AES_CBC_PKCS7Padding) {
            return new StorageCipherImplementationAES18(keyCipher, cipher, wrappedKeyBase64);
        }
        throw new Exception("Blob-based init not supported for algorithm: " + algorithm.name());
    }

    /**
     * Dynamically selects the appropriate StorageCipher implementation based on
     * the KeyCipher type and StorageCipherAlgorithm.
     */
    private StorageCipher createStorageCipher(Context context, KeyCipher keyCipher,
                                               Cipher cipher, StorageCipherAlgorithm algorithm) throws Exception {
        // For AES_GCM_NoPadding, choose implementation based on KeyCipher type
        if (algorithm == StorageCipherAlgorithm.AES_GCM_NoPadding) {
            if (isKeyStoreKeyCipher(keyCipher)) {
                // Use KeyStore-based implementation (biometric/PIN auth capable)
                return new StorageCipherImplementationAES23(context, keyCipher, cipher);
            } else {
                // Use RSA-wrapped implementation (standard secure storage)
                return new StorageCipherImplementationGCM(context, keyCipher, cipher);
            }
        }

        // For other algorithms, use the function from enum
        if (algorithm.storageCipher == null) {
            throw new Exception("No implementation available for algorithm: " + algorithm.name());
        }
        return algorithm.storageCipher.apply(context, keyCipher, cipher);
    }

    /**
     * Checks if the KeyCipher uses KeyStore (AES) vs RSA wrapping.
     */
    private boolean isKeyStoreKeyCipher(KeyCipher keyCipher) {
        return keyCipher instanceof KeyCipherImplementationAES23;
    }

    public KeyCipher getCurrentKeyCipher(Context context) throws Exception {
        return currentKeyAlgorithm.keyCipher.apply(context, config);
    }

    public KeyCipher getSavedKeyCipher(Context context) throws Exception {
        return savedKeyAlgorithm.keyCipher.apply(context, config);
    }

    public void storeCurrentAlgorithms(SharedPreferences.Editor editor) {
        editor.putString(ELEMENT_PREFERENCES_ALGORITHM_KEY, currentKeyAlgorithm.name());
        editor.putString(ELEMENT_PREFERENCES_ALGORITHM_STORAGE, currentStorageAlgorithm.name());
    }
}
