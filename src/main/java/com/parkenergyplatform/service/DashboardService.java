package com.parkenergyplatform.service;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final BusinessWorkspaceService workspaceService;

    public DashboardService(BusinessWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    public Map<String, Object> summary(Long rootOrgId) {
        return workspaceService.cockpit(rootOrgId);
    }
}
