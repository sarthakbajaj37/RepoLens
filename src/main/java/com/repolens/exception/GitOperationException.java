package com.repolens.exception;

/**
 * Thrown when a Git operation (clone, fetch, etc.) fails.
 */
public class GitOperationException extends RuntimeException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
