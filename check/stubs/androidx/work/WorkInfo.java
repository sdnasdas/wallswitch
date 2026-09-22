package androidx.work;

/** 本地类型检查桩：真实类来自 androidx.work 库（2.9.1 中 getNextScheduleTimeMillis 为公开 API）。 */
public class WorkInfo {

    /** 任务状态。 */
    public enum State {
        ENQUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        BLOCKED,
        CANCELLED
    }

    public State getState() {
        return null;
    }

    /** 最早具备运行条件的时刻（毫秒墙钟时间），不保证真实执行时间。 */
    public long getNextScheduleTimeMillis() {
        return 0;
    }
}