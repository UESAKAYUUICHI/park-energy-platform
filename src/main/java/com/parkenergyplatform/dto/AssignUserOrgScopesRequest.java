package com.parkenergyplatform.dto;

import java.util.List;

import jakarta.validation.Valid;

public record AssignUserOrgScopesRequest(
        List<@Valid UserOrgScopeItem> scopes
) {
}
