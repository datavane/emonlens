package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.engine.event.EventService;
import io.emonlens.server.engine.event.EventType;
import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.mapper.EventMapper;

import javax.annotation.Resource;

/**
 * 无中心化水平扩展的核心保障：et_event 原子认领。
 * 多线程（模拟多实例）并发争抢同一批事件，断言不重不漏。
 * worker 线程关闭避免后台 poller 参与竞争；使用独立命名 H2 库，与其他测试类的数据与存活 worker 隔离。
 */
@SpringBootTest(properties = {
        "emonlens.worker.enabled=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.platform=h2",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql",
        "spring.datasource.url=jdbc:h2:mem:emonlens-claim;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class EventClaimConcurrencyTest {

    @Resource
    private EventService eventService;

    @Resource
    private EventMapper eventMapper;

    @Test
    void concurrentClaimsAreMutuallyExclusive() throws Exception {
        try {
            int total = 20;
            for (int i = 0; i < total; i++) {
                eventService.enqueue(EventType.TRACE_COMPLETED, "ws-default", "claim-agent",
                        "{\"traceId\":\"claim-trace-" + i + "\"}");
            }

            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threads);
            Set<String> claimedIds = ConcurrentHashMap.newKeySet();
            AtomicInteger claimOps = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        for (int round = 0; round < 5; round++) {
                            List<EventEntity> claimed = eventService.claimBatch(5);
                            claimOps.incrementAndGet();
                            for (EventEntity event : claimed) {
                                // 同一事件只能被认领一次
                                assertThat(claimedIds.add(event.getId()))
                                        .as("event %s claimed twice", event.getId()).isTrue();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // 不重（上面断言）且不漏：所有事件都被认领
            assertThat(claimedIds).hasSize(total);
            Long doneCount = eventMapper.selectCount(Wrappers.<EventEntity>lambdaQuery()
                    .eq(EventEntity::getStatus, EventEntity.STATUS_CLAIMED));
            assertThat(doneCount).isEqualTo((long) total);
        } finally {
            eventMapper.delete(Wrappers.emptyWrapper());
        }
    }
}
