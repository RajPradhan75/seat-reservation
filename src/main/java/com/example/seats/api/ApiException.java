package com.example.seats.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "INVALID_REQUEST", message);
    }
    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "The requested resource was not found.");
    }
}
