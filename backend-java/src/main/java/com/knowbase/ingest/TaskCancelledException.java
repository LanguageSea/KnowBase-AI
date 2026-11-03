package com.knowbase.ingest;

/** 文档在外部处理期间进入删除态，当前任务结果必须整体回滚并丢弃。 */
public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException(String message) {
        super(message);
    }
}
