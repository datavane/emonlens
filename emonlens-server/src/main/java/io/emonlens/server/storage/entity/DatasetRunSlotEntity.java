package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 数据库级数据集运行准入槽（固定槽位池 GLOBAL#1..N）。
 *
 * <p>active run 的“先查后插”在多个实例间有竞态；本表用主键唯一约束把准入变成一次原子 insert。
 * 槽位归属由 run 状态决定，terminal run 的残留槽位可被下一次受理清理。</p>
 */
@Data
@TableName("et_dataset_run_slot")
public class DatasetRunSlotEntity {

    public static final String GLOBAL_SLOT = "GLOBAL";

    /** 固定槽位池 key；index 从 1 开始。 */
    public static String slotKey(int index) {
        return GLOBAL_SLOT + "#" + index;
    }

    @TableId(type = IdType.INPUT)
    private String slotKey;

    private String runId;

    private LocalDateTime acquiredAt;

    private LocalDateTime expiresAt;
}
