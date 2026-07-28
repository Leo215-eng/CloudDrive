package com.disk.files.domain.service.impl;

import com.disk.files.domain.request.FileSearchParamVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserFileServiceImplTest {

    @Test
    void pagesMergedProductionResults() {
        assertEquals(List.of(3, 4), UserFileServiceImpl.pageResult(List.of(1, 2, 3, 4, 5), 2, 2));
        assertEquals(List.of(), UserFileServiceImpl.pageResult(List.of(1, 2), 3, 2));
    }

    @Test
    void usesSafeSearchPageDefaults() {
        FileSearchParamVO request = new FileSearchParamVO();
        assertEquals(1, request.getPageNum());
        assertEquals(20, request.getPageSize());
    }
}
