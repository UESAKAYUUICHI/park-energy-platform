package com.parkenergyplatform.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账单催缴记录及逾期应收查询。 */
@Service
public class BillingCollectionService {
    private static final Set<String> COLLECTION_TYPES = Set.of("NOTICE", "PHONE", "EMAIL", "SMS", "STOP_SERVICE");

    private final JdbcTemplate jdbcTemplate;
    private final BusinessDataAccessService accessService;

    public BillingCollectionService(JdbcTemplate jdbcTemplate, BusinessDataAccessService accessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessService = accessService;
    }

    public List<Map<String, Object>> records(long billId) {
        accessService.assertBillAccess(billId);
        return jdbcTemplate.queryForList("""
                SELECT * FROM billing_collection_record
                WHERE bill_id = ?
                ORDER BY collection_time DESC, id DESC
                """, billId);
    }

    @Transactional
    public Map<String, Object> create(long billId, Map<String, Object> body) {
        accessService.assertBillAccess(billId);
        String collectionType = requiredType(body.get("collectionType"));
        String operator = requiredText(body.get("operator"), "operator");
        String content = optionalText(body.get("content"));
        String result = optionalText(body.get("result"));
        LocalDateTime followTime = parseDateTime(body.get("nextFollowTime"));
        jdbcTemplate.update("""
                INSERT INTO billing_collection_record
                  (bill_id, collection_type, operator, content, result, next_follow_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """, billId, collectionType, operator, content, result,
                followTime == null ? null : Timestamp.valueOf(followTime));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("billId", billId);
        response.put("collectionType", collectionType);
        response.put("created", true);
        return response;
    }

    public PageResult<Map<String, Object>> overdue(Map<String, String> params) {
        int pageNum = positive(params.get("pageNum"), 1);
        int pageSize = Math.min(positive(params.get("pageSize"), 20), 200);
        String keyword = optionalText(params.get("keyword"));
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE b.bill_status = 'ISSUED' AND b.pay_status IN (0, 4) AND b.outstanding_amount > 0 AND b.due_date < ?");
        args.add(java.sql.Date.valueOf(LocalDate.now()));
        if (keyword != null) {
            where.append(" AND (b.bill_no LIKE ? OR a.account_name LIKE ? OR b.tenant_name_snapshot LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        where.append(accessService.scopeSql("a.org_id", args));
        String from = " FROM billing_bill b JOIN billing_account a ON a.id = b.account_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        java.util.ArrayList<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNum - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT b.*, a.account_name, a.org_id, DATEDIFF(CURDATE(), b.due_date) AS overdue_days
                """ + from + where + " ORDER BY b.due_date ASC, b.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return PageResult.of(rows, total == null ? 0 : total, pageNum, pageSize);
    }

    private String requiredType(Object value) {
        String type = requiredText(value, "collectionType").toUpperCase();
        if (!COLLECTION_TYPES.contains(type)) {
            throw new BusinessException("collectionType 仅支持 " + COLLECTION_TYPES);
        }
        return type;
    }

    private String requiredText(Object value, String field) {
        String text = optionalText(value);
        if (text == null) {
            throw new BusinessException(field + " 不能为空");
        }
        return text;
    }

    private String optionalText(Object value) {
        if (value == null) return null;
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private LocalDateTime parseDateTime(Object value) {
        String text = optionalText(value);
        if (text == null) return null;
        try {
            return LocalDateTime.parse(text.replace("Z", ""));
        } catch (RuntimeException exception) {
            throw new BusinessException("nextFollowTime 必须为 ISO 日期时间");
        }
    }

    private int positive(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (RuntimeException exception) {
            return defaultValue;
        }
    }
}
