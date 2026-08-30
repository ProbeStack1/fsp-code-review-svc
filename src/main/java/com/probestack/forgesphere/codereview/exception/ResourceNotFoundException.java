package com.probestack.forgesphere.codereview.exception;

/** Thrown when a code-review record (or an upstream resource it needs) does not exist. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
