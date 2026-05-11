package me.apet97.breakcompliance.clockify;

public class ClockifyApiException extends RuntimeException {

    private final int statusCode;

    public ClockifyApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ClockifyApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
