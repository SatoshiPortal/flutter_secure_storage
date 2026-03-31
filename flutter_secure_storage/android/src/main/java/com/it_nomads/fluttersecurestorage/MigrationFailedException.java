package com.it_nomads.fluttersecurestorage;

/**
 * Exception thrown when migration has failed and retry is prevented by flag.
 * The cause contains the original exception that triggered the migration attempt.
 */
public class MigrationFailedException extends Exception {
    
    public MigrationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public MigrationFailedException(String message) {
        super(message);
    }
}
