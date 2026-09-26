package com.apigw.proxy.accesslog;

import com.apigw.domain.accesslog.AccessLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 批量落库器测试，覆盖攒批三边界与失败口径：
 * - 攒够条数立即落；不够条数由时间边界兜底落；
 * - 正常退出把手里没落完的冲库，不丢；
 * - 队列满非阻塞、不抛异常（丢计数）；
 * - 整批失败转逐条兜底，坏一条不连累好的；writer 异常不外冒。
 */
class AccessLogBatchWriterTest {

    private AccessLogBatchWriter writer;

    private static AccessLogRecord row(String no) {
        return new AccessLogRecord(no, "r", "app", "1.1.1.1", "GET", "/p",
                200, 1, LocalDateTime.now());
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    @Test
    void flushesWhenBatchSizeReached() throws Exception {
        CountDownLatch saved = new CountDownLatch(5);
        List<List<AccessLogRecord>> batches = new CopyOnWriteArrayList<>();
        writer = new AccessLogBatchWriter(batch -> {
            batches.add(batch);
            batch.forEach(r -> saved.countDown());
        }, new AccessLogProperties(true, "t", 5, Duration.ofSeconds(10),
                100, Duration.ofSeconds(2), "X-Request-Id", "X-App-No"));
        writer.start();

        for (int i = 0; i < 5; i++) {
            writer.record(row("n" + i));
        }
        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(5);
    }

    @Test
    void flushesOnLingerTimeout_whenBelowBatchSize() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        writer = new AccessLogBatchWriter(batch -> saved.countDown(),
                new AccessLogProperties(true, "t", 200, Duration.ofMillis(200),
                        100, Duration.ofSeconds(2), "X-Request-Id", "X-App-No"));
        long t0 = System.nanoTime();
        writer.start();
        writer.record(row("only-one"));

        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        // 时间边界：约一个 linger 周期落出，而不是等条数攒满
        assertThat(Duration.ofNanos(System.nanoTime() - t0).toMillis()).isLessThan(900);
    }

    @Test
    void shutdownDrainsQueuedRows_nothingLost() {
        List<AccessLogRecord> saved = new CopyOnWriteArrayList<>();
        writer = new AccessLogBatchWriter(saved::addAll,
                // linger 故意拉长，靠 shutdown 触发收尾
                new AccessLogProperties(true, "t", 200, Duration.ofSeconds(30),
                        1000, Duration.ofSeconds(3), "X-Request-Id", "X-App-No"));
        writer.start();
        for (int i = 0; i < 50; i++) {
            writer.record(row("s" + i));
        }
        writer.shutdown();
        assertThat(saved).hasSize(50);
    }

    @Test
    void queueFull_dropsWithoutBlockingOrThrowing() {
        // 容量 2、sink 永久阻塞占住 worker，造满队列
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        writer = new AccessLogBatchWriter(batch -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, new AccessLogProperties(true, "t", 1, Duration.ofMillis(50),
                2, Duration.ofSeconds(1), "X-Request-Id", "X-App-No"));
        writer.start();
        writer.record(row("a")); // worker 取走，阻塞在 sink
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        writer.record(row("b")); // 进队列
        writer.record(row("c")); // 进队列
        long t0 = System.nanoTime();
        writer.record(row("d")); // 队列满 → 丢弃，必须立即返回不抛
        assertThat(Duration.ofNanos(System.nanoTime() - t0).toMillis()).isLessThan(100);
        assertThat(writer.droppedCount()).isGreaterThanOrEqualTo(1);
        release.countDown();
    }

    @Test
    void bulkFailure_fallsBackToOneByOne_goodRowsSurvive() throws Exception {
        CountDownLatch goodRows = new CountDownLatch(2);
        List<AccessLogRecord> saved = new CopyOnWriteArrayList<>();
        AtomicBoolean bulkFailedOnce = new AtomicBoolean();
        writer = new AccessLogBatchWriter(batch -> {
            if (batch.size() > 1) {
                bulkFailedOnce.set(true);
                throw new RuntimeException("模拟整批失败（库抖动）");
            }
            // 逐条：奇数编号的行继续失败，偶数的能救回来
            if (batch.get(0).requestNo().equals("bad")) {
                throw new RuntimeException("模拟坏行");
            }
            saved.addAll(batch);
            goodRows.countDown();
        }, new AccessLogProperties(true, "t", 3, Duration.ofSeconds(10),
                100, Duration.ofSeconds(2), "X-Request-Id", "X-App-No"));
        writer.start();

        writer.record(row("good1"));
        writer.record(row("bad"));
        writer.record(row("good2"));

        assertThat(goodRows.await(2, TimeUnit.SECONDS)).isTrue();
        await().untilAsserted(() -> {
            assertThat(bulkFailedOnce.get()).isTrue();
            assertThat(writer.failedCount()).isEqualTo(1);
        });
        assertThat(saved).extracting(AccessLogRecord::requestNo)
                .containsExactlyInAnyOrder("good1", "good2"); // 坏行不连累好行
    }

    @Test
    void recordNeverThrows_whenSinkKeepsFailing() throws Exception {
        CountDownLatch attempts = new CountDownLatch(6);
        writer = new AccessLogBatchWriter(batch -> {
            attempts.countDown();
            throw new RuntimeException("库一直挂");
        }, new AccessLogProperties(true, "t", 2, Duration.ofMillis(20),
                100, Duration.ofSeconds(2), "X-Request-Id", "X-App-No"));
        writer.start();
        for (int i = 0; i < 4; i++) {
            writer.record(row("x" + i));
        }
        assertThat(attempts.await(2, TimeUnit.SECONDS)).isTrue();
        // worker 没死，还能继续收
        writer.record(row("alive"));
        assertThat(writer.failedCount()).isGreaterThanOrEqualTo(4);
    }
}
