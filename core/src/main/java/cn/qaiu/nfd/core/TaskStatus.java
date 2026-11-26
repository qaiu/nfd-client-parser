package cn.qaiu.nfd.core;

public enum TaskStatus {
    QUEUED("排队中"),
    RUNNING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean canResume() {
        return this == PAUSED || this == FAILED;
    }
}
