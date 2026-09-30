package com.parkenergyplatform.dto;

import java.util.List;

/** Only the relations that changed in one permission assignment operation. */
public record AssignIdsDeltaRequest(List<Long> addedIds, List<Long> removedIds) {
}
