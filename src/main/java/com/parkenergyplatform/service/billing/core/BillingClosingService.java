package com.parkenergyplatform.service.billing.core;

import com.parkenergyplatform.service.billing.core.*;
import com.parkenergyplatform.service.billing.finance.*;
import com.parkenergyplatform.service.billing.settlement.*;
import com.parkenergyplatform.service.billing.config.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps period closing and archive-package preparation in one transaction. */
@Service
public class BillingClosingService {
    private final BillingPeriodService periods;
    private final BillingSettlementArchiveService archives;

    public BillingClosingService(BillingPeriodService periods, BillingSettlementArchiveService archives) {
        this.periods = periods;
        this.archives = archives;
    }

    @Transactional
    public Map<String, Object> closeAndPrepare(long periodId, String operator) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", periods.close(periodId, operator));
        result.put("archive", archives.prepare(periodId, operator));
        return result;
    }
}
