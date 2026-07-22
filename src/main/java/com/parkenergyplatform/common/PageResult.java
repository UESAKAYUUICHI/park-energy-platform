package com.parkenergyplatform.common;

import java.util.List;

public record PageResult<T>(List<T> records, long total, int pageNum, int pageSize, long pages) {
    public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(records, total, pageNum, pageSize, pages);
    }
}
