package com.cardpricer.exception;

/**
 * Thrown when a Scryfall API request fails — for example, when a card or set
 * is not found (HTTP 404) or when the network request encounters an error.
 */
public class ScryfallApiException extends Exception {
    private final int statusCode;
    public int statusCode() { return statusCode; }
    public ScryfallApiException(int statusCode,String message) { super(message);this.statusCode=statusCode; }

    /**
     * Constructs a new exception with the given detail message.
     *
     * @param message human-readable description of the failure
     */
    public ScryfallApiException(String message) {
        super(message);
        statusCode=0;
    }

    /**
     * Constructs a new exception with a detail message and a root cause.
     *
     * @param message human-readable description of the failure
     * @param cause   the underlying exception that triggered this one
     */
    public ScryfallApiException(String message, Throwable cause) {
        super(message, cause);
        statusCode=0;
    }
}
