package com.flowsense.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Represents an annotation on a class or method.
 */
@Data
@Builder
public class ParsedAnnotation {
    private String name;                    // e.g. "RestController"
    private String fullName;               // e.g. "org.springframework...RestController"
    private Map<String, String> attributes; // e.g. {value: "/api"}
}
