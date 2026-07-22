package com.parkenergyplatform.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordRequest(@NotBlank String password) {
}
