package io.emonlens.server.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.junit.jupiter.api.Test;

class PageVOTest {

    @Test
    void safePageClampsPageNumberAndPageSize() {
        Page<Object> page = PageVO.safePage(-5, 100000);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1000);
    }

    @Test
    void safePageKeepsNormalPagination() {
        Page<Object> page = PageVO.safePage(3, 50);
        assertThat(page.getCurrent()).isEqualTo(3);
        assertThat(page.getSize()).isEqualTo(50);
    }
}
