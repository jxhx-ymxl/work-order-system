package com.workorder.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriageResult {

    private String suggestedType;
    private Integer suggestedPriority;

    public static TriageResult fallback() {
        return new TriageResult("OTHER", 0);
    }
}
