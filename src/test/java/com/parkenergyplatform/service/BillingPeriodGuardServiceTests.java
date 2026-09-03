package com.parkenergyplatform.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.parkenergyplatform.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 账期写保护（关账屏障）测试：已归档账期必须拒绝一切计费域写入，
 * 未归档或账期缺失时按各自语义放行，对象不存在时给出 404。
 */
class BillingPeriodGuardServiceTests {
    private JdbcTemplate jdbc;
    private BillingPeriodGuardService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new BillingPeriodGuardService(jdbc);
    }

    private Map<String, Object> archivedRow(Object archived) {
        return Map.of("period_status", "CLOSED", "period_code", "2026-08", "archived", archived);
    }

    @Test
    void missingBillIsRejectedWith404() {
        when(jdbc.queryForList(anyString(), eq(1L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.assertBillWritable(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账单不存在");
    }

    @Test
    void archivedPeriodBlocksBillModification() {
        when(jdbc.queryForList(anyString(), eq(2L))).thenReturn(List.of(archivedRow(1L)));
        assertThatThrownBy(() -> service.assertBillWritable(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026-08 账期已归档");
    }

    @Test
    void openPeriodAllowsBillModification() {
        when(jdbc.queryForList(anyString(), eq(3L))).thenReturn(List.of(archivedRow(0L)));
        assertThatCode(() -> service.assertBillWritable(3L)).doesNotThrowAnyException();
    }

    @Test
    void archivedFlagReturnedAsStringIsStillRecognized() {
        when(jdbc.queryForList(anyString(), eq(4L))).thenReturn(List.of(archivedRow("1")));
        assertThatThrownBy(() -> service.assertBillWritable(4L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账期已归档");
    }

    @Test
    void missingPaymentIsRejectedWith404() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(9L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.assertPaymentWritable(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收款记录不存在");
    }

    @Test
    void paymentDelegatesGuardToItsBill() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(10L))).thenReturn(List.of(3L));
        when(jdbc.queryForList(anyString(), eq(3L))).thenReturn(List.of(archivedRow(1L)));
        assertThatThrownBy(() -> service.assertPaymentWritable(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账期已归档");
        verify(jdbc).queryForList(anyString(), eq(3L));
    }

    @Test
    void missingAccountIsRejectedWith404() {
        when(jdbc.queryForList(anyString(), eq("2026-08"), eq(77L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.assertAccountCycleWritable(77L, "2026-08"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("计费账户不存在");
    }

    @Test
    void archivedPeriodBlocksRebillingForAccountCycle() {
        when(jdbc.queryForList(anyString(), eq("2026-08"), eq(78L))).thenReturn(List.of(archivedRow(1L)));
        assertThatThrownBy(() -> service.assertAccountCycleWritable(78L, "2026-08"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止再次出账");
    }

    @Test
    void orgCycleWithoutPeriodRowIsTreatedAsWritable() {
        when(jdbc.queryForList(anyString(), eq(9L), eq("2026-09"))).thenReturn(List.of());
        assertThatCode(() -> service.assertOrgCycleWritable(9L, "2026-09")).doesNotThrowAnyException();
    }

    @Test
    void archivedPeriodBlocksBatchChangesForOrgCycle() {
        when(jdbc.queryForList(anyString(), eq(9L), eq("2026-08"))).thenReturn(List.of(archivedRow(1L)));
        assertThatThrownBy(() -> service.assertOrgCycleWritable(9L, "2026-08"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止修改出账批次");
    }

    @Test
    void periodGuardsRejectMissingAndArchivedPeriods() {
        when(jdbc.queryForList(anyString(), eq(500L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.assertPeriodWritable(500L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账期不存在");

        when(jdbc.queryForList(anyString(), eq(501L))).thenReturn(List.of(archivedRow(1L)));
        assertThatThrownBy(() -> service.assertPeriodWritable(501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止录入或修改流水");

        when(jdbc.queryForList(anyString(), eq(502L))).thenReturn(List.of(archivedRow(0L)));
        assertThatCode(() -> service.assertPeriodWritable(502L)).doesNotThrowAnyException();
    }
}
