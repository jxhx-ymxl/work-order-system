package com.workorder.service;

import com.workorder.common.dto.TriageResult;

public interface OrderTriageService {

    TriageResult triage(String title, String content);
}
