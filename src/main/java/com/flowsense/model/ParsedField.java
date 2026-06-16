package com.flowsense.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a field/member variable in a Java class.
 */
@Data
@Builder
public class ParsedField {
    private String fieldName;
    private String fieldType;
    private boolean isPrivate;
    private boolean isStatic;
    private boolean isFinal;
    private String annotation;    // e.g. @Autowired, @Value
    private int lineNumber;
}
