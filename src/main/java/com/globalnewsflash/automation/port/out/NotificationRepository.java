package com.globalnewsflash.automation.port.out;

import com.globalnewsflash.automation.domain.Notice;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface NotificationRepository {
    void insert(Notice notice);
    List<Notice> due(long now);
    int claim(String id, long until, long now);
    void finish(String id, int attempts, String state, long next, String error);
    List<Notice> list();
}
