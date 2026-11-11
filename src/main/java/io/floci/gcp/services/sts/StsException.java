package io.floci.gcp.services.sts;

public class StsException extends RuntimeException {

	private final String error;

	StsException(String error, String message) {
		super(message);
		this.error = error;
	}

	public String error() {
		return error;
	}
}
