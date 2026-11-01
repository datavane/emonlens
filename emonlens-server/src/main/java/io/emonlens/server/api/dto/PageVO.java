package io.emonlens.server.api.dto;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {

    private long pageNumber;
    private long pageSize;
    private long total;
    private List<T> records;

    /**
     * 防御性分页参数收敛：避免 pageSize=100000 或负页码造成的批量扫描/异常。
     * 当前 UI 最大使用 1000/页（评估结果导出），所以这里统一夹紧到 1..1000。
     */
    public static <S> Page<S> safePage(long pageNumber, long pageSize) {
        long safeNumber = Math.max(1, pageNumber);
        long safeSize = Math.max(1, Math.min(pageSize, 1000));
        return new Page<>(safeNumber, safeSize);
    }

    public static <S, T> PageVO<T> from(Page<S> page, List<T> records) {
        return new PageVO<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    public static <T> PageVO<T> of(List<T> records, long total, long pageNumber, long pageSize) {
        return new PageVO<>(pageNumber, pageSize, total, records);
    }
}
