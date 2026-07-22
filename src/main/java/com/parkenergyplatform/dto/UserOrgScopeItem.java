package com.parkenergyplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UserOrgScopeItem(
        @NotNull Long orgId,
        @NotBlank String scopeMode
) {
}
