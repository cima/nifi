package org.apache.nifi.processors.azure.storage.utils;

import org.apache.nifi.flowfile.FlowFile;

public class FlowFileResultCarrier<T> {

	private FlowFile flowFile;
	private T result;
	private Throwable exception;
	
	public FlowFileResultCarrier(FlowFile flowFile, T result) {
		this.flowFile = flowFile;
		this.result = result;
	}
	
	public FlowFileResultCarrier(FlowFile flowFile, T result, Throwable exception) {
		this.flowFile = flowFile;
		this.result = result;
		this.exception = exception;
	}
	
	public FlowFile getFlowFile() {
		return flowFile;
	}
	public void setFlowFile(FlowFile flowFile) {
		this.flowFile = flowFile;
	}
	public T getResult() {
		return result;
	}
	public void setResult(T result) {
		this.result = result;
	}
	public Throwable getException() {
		return exception;
	}
	public void setException(Throwable exception) {
		this.exception = exception;
	}
	
}
