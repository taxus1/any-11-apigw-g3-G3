package com.apigw.proxy.accesslog;

import com.apigw.domain.accesslog.AccessLogRecord;
import com.apigw.domain.accesslog.AccessLogSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问流水的异步批量落库器。转发主路径对它只有一个动作：{@link #record} 非阻塞入队，
 * 库多慢、多抖都不经过请求线程，绝不拖慢转发。
 *
 * 攒批的三条边界（参数见 {@link AccessLogProperties}）：
 * <ol>
 *   <li><b>条数边界</b>：攒够 {@code batchSize} 条立刻落；worker 一次最多就取这么多去落，
 *       不会无限攒；</li>
 *   <li><b>时间边界</b>：不够一批时，第一条进来后最多等 {@code linger}（默认 1s）必落一次，
 *       低峰期流水也不会压在内存里；</li>
 *   <li><b>退出边界</b>：{@link #shutdown()} 先封口队列，把手里/队列里没落完的逐批冲库，
 *       最多宽限 {@code shutdownWait}（默认 5s），超时放弃——不丢数据也不无限拖住停机。</li>
 * </ol>
 *
 * 背压：入队队列有界（默认 10000）。满了 {@code record} 直接丢这条并计数告警，
 * 由 {@code access-log} 的调用线程零等待返回——丢流水是可接受的降级，拖垮转发不是。
 *
 * 落库失败：{@link AccessLogSink} 一批全成全败，本类再对失败批做逐条兜底（坏一条不连累整批）；
 * 还失败的只打 error 日志。异常在 worker 内全部收口，永远不会冒到反应式链路上。
 */
@Slf4j
public class AccessLogBatchWriter {

    private final AccessLogSink sink;
    private final AccessLogProperties props;

    private final BlockingQueue<AccessLogRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running = true;
    private Thread worker;

    public AccessLogBatchWriter(AccessLogSink sink, AccessLogProperties props) {
        this.sink = sink;
        this.props = props;
        this.queue = new ArrayBlockingQueue<>(props.queueCapacity());
    }

    @PostConstruct
    public void start() {
        worker = new Thread(this::runLoop, "access-log-db-writer");
        // 守护线程：即使停机钩子出意外，也不拖住 JVM；正常退出仍走 shutdown() 主动冲库
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 转发链路唯一会调的方法。非阻塞、不抛异常：
     * 队列满丢一条（计数+告警），保证反应式事件循环永远不被库/队列卡住。
     */
    public void record(AccessLogRecord entry) {
        if (!running || entry == null) {
            return;
        }
        if (queue.offer(entry)) {
            return;
        }
        long n = dropped.incrementAndGet();
        if (n == 1 || n % 1000 == 0) {
            log.warn("访问流水队列已满（容量 {}），累计丢弃 {} 条流水（转发不受影响）",
                    props.queueCapacity(), n);
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedCount() {
        return failed.get();
    }

    int queueSize() {
        return queue.size();
    }

    /** worker 主循环：凑批（条数 or 时间，先到先触发）→ 落库，循环往复。 */
    private void runLoop() {
        List<AccessLogRecord> batch = new ArrayList<>(props.batchSize());
        while (running || !queue.isEmpty()) {
            try {
                AccessLogRecord first = running
                        ? queue.poll(1, TimeUnit.SECONDS) : queue.poll();
                if (first == null) {
                    continue;
                }
                batch.add(first);
                // 时间边界：拿到第一条起，最多再等 linger，期间凑够条数也提前走。
                // 退出封口后不再等待（waitNanos 截到 0），立刻把队列抽干，避免停机白等一个 linger
                long deadlineNanos = System.nanoTime() + props.linger().toNanos();
                while (batch.size() < props.batchSize()) {
                    long waitNanos = running ? deadlineNanos - System.nanoTime() : 0L;
                    if (waitNanos <= 0) {
                        break;
                    }
                    AccessLogRecord next = queue.poll(waitNanos, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break; // 到点了
                    }
                    batch.add(next);
                }
                // 给 sink 的是快照副本：sink 可能持有这批（测试/异步处理），
                // worker 随后 clear 自己的缓冲绝不能把已交出去的这批清空
                flush(List.copyOf(batch));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // 任何意外都不能打死 worker：它一死后面的流水全丢且无人感知
                log.error("访问流水批量落库循环异常，本批 {} 条", batch.size(), t);
                failed.addAndGet(batch.size());
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<AccessLogRecord> batch) {
        try {
            sink.saveBatch(batch);
        } catch (Exception bulkError) {
            // 整批多语句失败（多为库抖动）：先逐条兜底，把好的救回来，只让真正写不进的丢
            saveOneByOne(batch, bulkError);
        }
    }

    /**
     * 逐条落：每条都是独立单语句（隐式事务，全成全败），绝不产生半条记录。
     * 逐条仍失败的只计数 + error 日志，不抛出——主职责是转发，写库失败最多丢日志。
     */
    private void saveOneByOne(List<AccessLogRecord> batch, Exception bulkError) {
        log.warn("访问流水整批落库失败（{} 条），转逐条兜底", batch.size(), bulkError);
        for (AccessLogRecord r : batch) {
            try {
                sink.saveBatch(List.of(r));
            } catch (Exception singleError) {
                long n = failed.incrementAndGet();
                if (n == 1 || n % 100 == 0) {
                    log.error("访问流水逐条落库仍失败，累计 {} 条；样例 requestNo={} path={}",
                            n, r.requestNo(), r.path(), singleError);
                }
            }
        }
    }

    /**
     * 正常退出：封口 → 唤醒 → 等 worker 把队列冲完（含逐条兜底），宽限到点就放弃。
     * 停机期间新来的 record 因 running=false 直接不入队，避免边退边进。
     */
    @PreDestroy
    public void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        if (worker != null) {
            try {
                worker.join(props.shutdownWait().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                log.warn("访问流水退出宽限 {} 已到，仍有未落流水，放弃等待（不拖住停机）",
                        props.shutdownWait());
            }
        }
        long d = dropped.get();
        long f = failed.get();
        if (d != 0 || f != 0) {
            log.warn("访问流水本次运行累计：队列满丢弃 {} 条，落库失败 {} 条", d, f);
        }
    }
}
