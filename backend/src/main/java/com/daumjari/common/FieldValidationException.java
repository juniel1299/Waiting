package com.daumjari.common;

import java.util.Map;

/** 입력 칸별 검증 실패. 400 VALIDATION_FAILED 와 함께 fields(칸 이름 → 안내)로 응답한다. */
public class FieldValidationException extends RuntimeException {

	private final Map<String, String> fields;

	public FieldValidationException(Map<String, String> fields) {
		super("invalid fields " + fields.keySet());
		this.fields = Map.copyOf(fields);
	}

	public Map<String, String> fields() {
		return fields;
	}

}
